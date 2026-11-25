package com.karoogfcompanion.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

object RouteGeometryEngine {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val MULTI_PASS_CORRIDOR_M = 65.0
    private const val MULTI_PASS_MIN_SEPARATION_M = 1_200.0
    private const val MAX_WPT_SNAP_CORRIDOR_M = 800.0

    data class IndexedPolyline(
        val points: List<LatLngPoint>,
        val cumulativeDistM: DoubleArray,
        val totalDistanceM: Double,
    )

    data class ProjectionResult(
        val distanceAlongRouteM: Double,
        val crossTrackDistanceM: Double,
    )

    fun buildIndexedPolyline(
        points: List<LatLngPoint>,
        declaredRouteDistanceM: Double? = null,
    ): IndexedPolyline {
        if (points.isEmpty()) {
            return IndexedPolyline(emptyList(), DoubleArray(0), declaredRouteDistanceM ?: 0.0)
        }
        val rawCum = DoubleArray(points.size)
        var acc = 0.0
        for (i in 1 until points.size) {
            acc += haversineMeters(points[i - 1], points[i])
            rawCum[i] = acc
        }
        val targetTotal = if (declaredRouteDistanceM != null && declaredRouteDistanceM > 1.0 && acc > 1.0) {
            val scale = declaredRouteDistanceM / acc
            if (scale in 0.90..1.10) {
                for (i in rawCum.indices) {
                    rawCum[i] *= scale
                }
            }
            declaredRouteDistanceM
        } else {
            max(acc, declaredRouteDistanceM ?: 0.0)
        }
        return IndexedPolyline(points, rawCum, targetTotal)
    }

    fun projectOntoPolyline(indexed: IndexedPolyline, target: LatLngPoint): ProjectionResult? {
        val pts = indexed.points
        if (pts.isEmpty()) return null
        if (pts.size == 1) {
            return ProjectionResult(0.0, haversineMeters(pts[0], target))
        }

        var bestCrossM = Double.MAX_VALUE
        var bestAlongM = 0.0
        val cosLat = cos(Math.toRadians(target.lat)).coerceAtLeast(0.05)

        for (i in 0 until pts.lastIndex) {
            val a = pts[i]
            val b = pts[i + 1]

            val ax = Math.toRadians(a.lng - target.lng) * cosLat * EARTH_RADIUS_M
            val ay = Math.toRadians(a.lat - target.lat) * EARTH_RADIUS_M
            val bx = Math.toRadians(b.lng - target.lng) * cosLat * EARTH_RADIUS_M
            val by = Math.toRadians(b.lat - target.lat) * EARTH_RADIUS_M

            val dx = bx - ax
            val dy = by - ay
            val segLenSq = dx * dx + dy * dy
            val t = if (segLenSq <= 1e-6) {
                0.0
            } else {
                ((-ax * dx - ay * dy) / segLenSq).coerceIn(0.0, 1.0)
            }

            val projX = ax + t * dx
            val projY = ay + t * dy
            val distM = hypot(projX, projY)

            if (distM < bestCrossM) {
                bestCrossM = distM
                val segStartDist = indexed.cumulativeDistM[i]
                val segEndDist = indexed.cumulativeDistM[i + 1]
                bestAlongM = segStartDist + t * (segEndDist - segStartDist)
            }
        }

        return ProjectionResult(bestAlongM, bestCrossM)
    }

    /**
     * Finds all distinct passes of a waypoint along the route polyline (supports loops/out-and-back).
     */
    fun findDistancesAlongRoute(
        indexed: IndexedPolyline,
        target: LatLngPoint,
    ): List<Double> {
        val pts = indexed.points
        if (pts.size < 2) return emptyList()

        val cosLat = cos(Math.toRadians(target.lat)).coerceAtLeast(0.05)

        data class Hit(val alongM: Double, val crossM: Double)

        val corridorHits = ArrayList<Hit>()
        var globalBestCross = Double.MAX_VALUE
        var globalBestAlong = 0.0

        for (i in 0 until pts.lastIndex) {
            val a = pts[i]
            val b = pts[i + 1]

            val ax = Math.toRadians(a.lng - target.lng) * cosLat * EARTH_RADIUS_M
            val ay = Math.toRadians(a.lat - target.lat) * EARTH_RADIUS_M
            val bx = Math.toRadians(b.lng - target.lng) * cosLat * EARTH_RADIUS_M
            val by = Math.toRadians(b.lat - target.lat) * EARTH_RADIUS_M

            val dx = bx - ax
            val dy = by - ay
            val segLenSq = dx * dx + dy * dy
            val t = if (segLenSq <= 1e-6) 0.0 else ((-ax * dx - ay * dy) / segLenSq).coerceIn(0.0, 1.0)

            val projX = ax + t * dx
            val projY = ay + t * dy
            val crossM = hypot(projX, projY)
            val alongM = indexed.cumulativeDistM[i] + t * (indexed.cumulativeDistM[i + 1] - indexed.cumulativeDistM[i])

            if (crossM < globalBestCross) {
                globalBestCross = crossM
                globalBestAlong = alongM
            }
            if (crossM <= MULTI_PASS_CORRIDOR_M) {
                corridorHits.add(Hit(alongM, crossM))
            }
        }

        if (corridorHits.isNotEmpty()) {
            val merged = ArrayList<Hit>()
            for (hit in corridorHits) {
                val last = merged.lastOrNull()
                if (last == null || hit.alongM - last.alongM > MULTI_PASS_MIN_SEPARATION_M) {
                    merged.add(hit)
                } else if (hit.crossM < last.crossM) {
                    merged[merged.lastIndex] = hit
                }
            }
            return merged.map { it.alongM }
        }

        return if (globalBestCross <= MAX_WPT_SNAP_CORRIDOR_M) {
            listOf(globalBestAlong)
        } else {
            emptyList()
        }
    }

    fun haversineMeters(a: LatLngPoint, b: LatLngPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val sinLat = sin(dLat * 0.5)
        val sinLng = sin(dLng * 0.5)
        val h = sinLat * sinLat +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sinLng * sinLng
        return 2.0 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt((1.0 - h).coerceAtLeast(0.0)))
    }
}
