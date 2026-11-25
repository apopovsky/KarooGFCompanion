package com.karoogfcompanion.domain

import kotlin.math.max
import kotlin.math.roundToInt

data class GaugeEvaluation(
    val zone: GaugeZone,
    val status: PowerDeviationStatus,
    val needleAngleDeg: Float?,
)

data class ResolvedPacingRouteState(
    val allPoints: List<RouteWaypointPoint>,
    val activeSegmentPoint: RouteWaypointPoint?,
    val nextPoint: RouteWaypointPoint?,
    val isNextPointApproaching: Boolean,
)

/**
 * Pure domain pacing engine:
 * - Resolves route waypoints onto route distance (without inventing synthetic waypoints)
 * - Identifies the active power segment and the next route point independently
 * - Maps current power against the active target range onto the 180-degree gauge scale
 */
object RoutePacingEngine {

    const val START_SEGMENT_SNAP_M = 250.0
    const val POINT_LEAD_HYSTERESIS_M = 15.0
    const val OVERLAPPING_POINT_DEDUP_M = 15.0

    const val ANGLE_MIN_DEG = 180f
    const val ANGLE_RED_LOW_END_DEG = 216f
    const val ANGLE_GREEN_START_DEG = 248f
    const val ANGLE_GREEN_END_DEG = 292f
    const val ANGLE_YELLOW_HIGH_END_DEG = 324f
    const val ANGLE_MAX_DEG = 360f

    fun resolveRoutePacing(
        indexedPolyline: RouteGeometryEngine.IndexedPolyline,
        routePois: List<RawRoutePoi>,
        riderDistanceAlongRouteM: Double,
        rejoinExtraDistanceM: Double = 0.0,
        singleTargetToleranceWatts: Int = 10,
        approachingDistanceMeters: Int = 500,
    ): ResolvedPacingRouteState {
        val totalDistM = indexedPolyline.totalDistanceM
        if (totalDistM <= 1.0 || routePois.isEmpty()) {
            return ResolvedPacingRouteState(
                allPoints = emptyList(),
                activeSegmentPoint = null,
                nextPoint = null,
                isNextPointApproaching = false,
            )
        }

        data class RawCandidate(
            val id: String,
            val rawName: String,
            val cleanName: String,
            val description: String?,
            val distanceAlongRouteM: Double,
            val targetRange: PowerTargetRange?,
            val originalOrder: Int,
        )

        val candidates = ArrayList<RawCandidate>()
        for ((poiIndex, poi) in routePois.withIndex()) {
            val rawName = poi.name?.trim().orEmpty()
            if (rawName.isEmpty()) continue

            val parsed = PowerTargetParser.parse(
                rawName = rawName,
                rawDescription = poi.description,
                singleTargetToleranceWatts = singleTargetToleranceWatts,
            )

            val routeDistances = if (poi.distancesAlongRouteM.isNotEmpty()) {
                poi.distancesAlongRouteM
            } else {
                val proj = RouteGeometryEngine.projectOntoPolyline(
                    indexed = indexedPolyline,
                    target = LatLngPoint(poi.lat, poi.lng),
                )
                if (proj != null) listOf(proj.distanceAlongRouteM) else emptyList()
            }

            // On multi-lap routes, collapse repeated passes to only the last passed and next upcoming pass
            val relevantDistances = if (routeDistances.size > 1) {
                val sortedDists = routeDistances.sorted()
                val nextUpcoming = sortedDists.firstOrNull {
                    it >= riderDistanceAlongRouteM - POINT_LEAD_HYSTERESIS_M
                }
                val lastPassed = sortedDists.lastOrNull {
                    it < riderDistanceAlongRouteM - POINT_LEAD_HYSTERESIS_M
                }
                listOfNotNull(lastPassed, nextUpcoming).distinct()
            } else {
                routeDistances
            }

            for ((occIdx, distM) in relevantDistances.withIndex()) {
                val clampedDist = distM.coerceIn(0.0, totalDistM)
                candidates.add(
                    RawCandidate(
                        id = if (relevantDistances.size > 1) "${poi.id}_$occIdx" else poi.id,
                        rawName = rawName,
                        cleanName = parsed.cleanName.ifBlank { rawName },
                        description = poi.description,
                        distanceAlongRouteM = clampedDist,
                        targetRange = parsed.targetRange,
                        originalOrder = poiIndex * 100 + occIdx,
                    )
                )
            }
        }

        if (candidates.isEmpty()) {
            return ResolvedPacingRouteState(
                allPoints = emptyList(),
                activeSegmentPoint = null,
                nextPoint = null,
                isNextPointApproaching = false,
            )
        }

        val sorted = candidates.sortedWith(
            compareBy<RawCandidate> { it.distanceAlongRouteM }.thenBy { it.originalOrder }
        )

        // Identify active power-bearing segment:
        // - If rider has passed (within lead hysteresis) one or more power-bearing points, pick the latest one.
        //   When two power-bearing points sit within OVERLAPPING_POINT_DEDUP_M, sorted order puts the later one last.
        // - Otherwise, if rider is at the very start (< START_SEGMENT_SNAP_M) and the first power-bearing point
        //   is also within START_SEGMENT_SNAP_M, activate that opening segment from km 0.
        val reachedPowerCandidates = sorted.filter {
            it.targetRange != null && it.distanceAlongRouteM <= riderDistanceAlongRouteM + POINT_LEAD_HYSTERESIS_M
        }
        val activePowerCandidate: RawCandidate? = if (reachedPowerCandidates.isNotEmpty()) {
            val latest = reachedPowerCandidates.last()
            // If there is another power candidate within OVERLAPPING_POINT_DEDUP_M ahead of latest that is also within hysteresis,
            // last() already wins by route distance & order.
            latest
        } else {
            val firstPower = sorted.firstOrNull { it.targetRange != null }
            if (firstPower != null &&
                riderDistanceAlongRouteM < START_SEGMENT_SNAP_M &&
                firstPower.distanceAlongRouteM <= START_SEGMENT_SNAP_M
            ) {
                firstPower
            } else {
                null
            }
        }

        // Next point on route: first point ahead of the rider (and not the active start segment itself)
        val nextCandidate = sorted.firstOrNull { cand ->
            val notActive = cand.id != activePowerCandidate?.id
            val aheadOfRider = cand.distanceAlongRouteM > riderDistanceAlongRouteM + POINT_LEAD_HYSTERESIS_M
            val skipOverlappingPowerSameSpot = activePowerCandidate != null &&
                cand.targetRange != null &&
                kotlin.math.abs(cand.distanceAlongRouteM - activePowerCandidate.distanceAlongRouteM) <= OVERLAPPING_POINT_DEDUP_M &&
                cand.originalOrder <= activePowerCandidate.originalOrder
            notActive && aheadOfRider && !skipOverlappingPowerSameSpot
        }

        val mappedPoints = sorted.map { cand ->
            val rawDelta = cand.distanceAlongRouteM - riderDistanceAlongRouteM
            val isActive = cand.id == activePowerCandidate?.id
            val isNext = cand.id == nextCandidate?.id
            val isPassed = !isNext && (
                cand.distanceAlongRouteM < riderDistanceAlongRouteM - POINT_LEAD_HYSTERESIS_M ||
                    isActive
                )
            val remainingM = if (rawDelta >= 0.0) {
                rawDelta + rejoinExtraDistanceM
            } else {
                0.0
            }

            RouteWaypointPoint(
                id = cand.id,
                rawName = cand.rawName,
                cleanName = cand.cleanName,
                description = cand.description,
                distanceAlongRouteM = cand.distanceAlongRouteM,
                remainingDistanceM = remainingM,
                targetRange = cand.targetRange,
                isPassed = isPassed,
                isActiveSegmentStart = isActive,
                isNextPoint = isNext,
            )
        }

        val activePoint = mappedPoints.firstOrNull { it.isActiveSegmentStart }
        val nextPoint = mappedPoints.firstOrNull { it.isNextPoint }
        val isApproaching = nextPoint != null &&
            nextPoint.remainingDistanceM > 0.0 &&
            nextPoint.remainingDistanceM <= approachingDistanceMeters.toDouble()

        return ResolvedPacingRouteState(
            allPoints = mappedPoints,
            activeSegmentPoint = activePoint,
            nextPoint = nextPoint,
            isNextPointApproaching = isApproaching,
        )
    }

    fun evaluateGauge(
        currentPowerWatts: Int?,
        isPowerAvailable: Boolean,
        target: PowerTargetRange?,
        yellowMarginMinWatts: Int = 15,
    ): GaugeEvaluation {
        if (target == null) {
            return GaugeEvaluation(
                zone = GaugeZone.NO_TARGET,
                status = if (!isPowerAvailable || currentPowerWatts == null) {
                    PowerDeviationStatus.NO_POWER
                } else {
                    PowerDeviationStatus.NO_TARGET
                },
                needleAngleDeg = null,
            )
        }

        if (!isPowerAvailable || currentPowerWatts == null) {
            return GaugeEvaluation(
                zone = GaugeZone.NO_TARGET,
                status = PowerDeviationStatus.NO_POWER,
                needleAngleDeg = null,
            )
        }

        val p = currentPowerWatts.coerceAtLeast(0)
        val minW = target.minWatts
        val maxW = target.maxWatts
        val deltaY = max(yellowMarginMinWatts.coerceAtLeast(5), target.widthWatts).toDouble()

        return when {
            p in minW..maxW -> {
                val t = if (maxW == minW) {
                    0.5f
                } else {
                    ((p - minW).toFloat() / (maxW - minW).toFloat()).coerceIn(0f, 1f)
                }
                val angle = ANGLE_GREEN_START_DEG + t * (ANGLE_GREEN_END_DEG - ANGLE_GREEN_START_DEG)
                GaugeEvaluation(
                    zone = GaugeZone.IN_TARGET,
                    status = PowerDeviationStatus.WITHIN_TARGET,
                    needleAngleDeg = angle,
                )
            }

            p < minW -> {
                val yellowFloor = minW - deltaY
                if (p >= yellowFloor) {
                    val t = ((p - yellowFloor) / deltaY).toFloat().coerceIn(0f, 0.99f)
                    val angle = ANGLE_RED_LOW_END_DEG + t * (ANGLE_GREEN_START_DEG - ANGLE_RED_LOW_END_DEG)
                    GaugeEvaluation(
                        zone = GaugeZone.LOW_YELLOW,
                        status = PowerDeviationStatus.LOW,
                        needleAngleDeg = angle.coerceAtMost(ANGLE_GREEN_START_DEG - 0.5f),
                    )
                } else {
                    val redFloor = (minW - 2.5 * deltaY).coerceAtLeast(0.0)
                    val span = (yellowFloor - redFloor).coerceAtLeast(1.0)
                    val t = ((p - redFloor) / span).toFloat().coerceIn(0f, 0.99f)
                    val angle = (ANGLE_MIN_DEG + 2f) + t * (ANGLE_RED_LOW_END_DEG - (ANGLE_MIN_DEG + 2f))
                    GaugeEvaluation(
                        zone = GaugeZone.LOW_RED,
                        status = PowerDeviationStatus.LOW,
                        needleAngleDeg = angle.coerceIn(ANGLE_MIN_DEG + 2f, ANGLE_RED_LOW_END_DEG - 0.5f),
                    )
                }
            }

            else -> {
                val yellowCap = maxW + deltaY
                if (p <= yellowCap) {
                    val t = ((p - maxW) / deltaY).toFloat().coerceIn(0.01f, 1f)
                    val angle = ANGLE_GREEN_END_DEG + t * (ANGLE_YELLOW_HIGH_END_DEG - ANGLE_GREEN_END_DEG)
                    GaugeEvaluation(
                        zone = GaugeZone.HIGH_YELLOW,
                        status = PowerDeviationStatus.HIGH,
                        needleAngleDeg = angle.coerceAtLeast(ANGLE_GREEN_END_DEG + 0.5f),
                    )
                } else {
                    val redCap = maxW + 2.5 * deltaY
                    val span = (redCap - yellowCap).coerceAtLeast(1.0)
                    val t = ((p - yellowCap) / span).toFloat().coerceIn(0.01f, 1f)
                    val angle = ANGLE_YELLOW_HIGH_END_DEG + t * ((ANGLE_MAX_DEG - 2f) - ANGLE_YELLOW_HIGH_END_DEG)
                    GaugeEvaluation(
                        zone = GaugeZone.HIGH_RED,
                        status = PowerDeviationStatus.HIGH,
                        needleAngleDeg = angle.coerceIn(ANGLE_YELLOW_HIGH_END_DEG + 0.5f, ANGLE_MAX_DEG - 2f),
                    )
                }
            }
        }
    }
}
