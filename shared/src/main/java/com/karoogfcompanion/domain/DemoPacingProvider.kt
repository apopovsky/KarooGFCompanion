package com.karoogfcompanion.domain

/**
 * Deterministic preview snapshot based on the Gran Fondo 7 Lagos 2026 waypoints
 * matching the visual concept reference (198 W inside 190-205 W, HR 152 bpm,
 * CAD 86 rpm, LAP AVG 194 W, NEXT Puerto Chapelco 2.4 km | 175-190 W).
 */
object DemoPacingProvider {

    private data class DemoSegment(
        val id: String,
        val rawName: String,
        val distanceM: Double,
    )

    val DEFAULT_DEMO_HR_ZONES: List<ZoneBound> = listOf(
        ZoneBound(0, 137),
        ZoneBound(138, 152),
        ZoneBound(153, 159),
        ZoneBound(160, 170),
        ZoneBound(171, 175),
        ZoneBound(176, 180),
        ZoneBound(181, 220),
    )

    val DEFAULT_DEMO_POWER_ZONES: List<ZoneBound> = listOf(
        ZoneBound(0, 130),
        ZoneBound(131, 177),
        ZoneBound(178, 213),
        ZoneBound(214, 248),
        ZoneBound(249, 284),
        ZoneBound(285, 355),
        ZoneBound(356, 2000),
    )

    private val SEVEN_LAGOS_SEGMENTS = listOf(
        DemoSegment("demo_0", "Largada a Chapelco (190-205W)", 0.0),
        DemoSegment("demo_1", "Puerto Chapelco (175-190W)", 14_500.0),
        DemoSegment("demo_2", "Techo Ruta 1180m (120-150W)", 17_600.0),
        DemoSegment("demo_3", "Subida Machonico (195-210W)", 27_000.0),
        DemoSegment("demo_4", "Cima Machonico (145-170W)", 30_400.0),
        DemoSegment("demo_5", "Hidratacion 1 (125-155W)", 42_400.0),
        DemoSegment("demo_6", "Subida Falkner (190-205W)", 48_100.0),
        DemoSegment("demo_7", "Cima Falkner (115-145W)", 51_600.0),
        DemoSegment("demo_8", "Sprint Oficial (155-180W)", 58_400.0),
        DemoSegment("demo_9", "Pie Pto. Escondido (195-210W)", 61_000.0),
    )

    fun buildDemoSnapshot(
        settings: GfCompanionSettings = GfCompanionSettings(),
        isSegmentOverlayOpen: Boolean = false,
        overlayRemainingSeconds: Int = 0,
        isCueSheetPlusInstalled: Boolean = false,
        strings: GfStrings = GfStrings.forLocale(),
    ): GfPacingSnapshot {
        val riderDistM = 12_100.0 // 2.4 km remaining to Puerto Chapelco at 14.5 km
        val totalDistM = 107_900.0
        val currentPower = 198

        val points = SEVEN_LAGOS_SEGMENTS.mapIndexed { idx, seg ->
            val parsed = PowerTargetParser.parse(
                rawName = seg.rawName,
                singleTargetToleranceWatts = settings.singleTargetToleranceWatts,
            )
            val remainingM = (seg.distanceM - riderDistM).coerceAtLeast(0.0)
            RouteWaypointPoint(
                id = seg.id,
                rawName = seg.rawName,
                cleanName = parsed.cleanName,
                description = null,
                distanceAlongRouteM = seg.distanceM,
                remainingDistanceM = remainingM,
                targetRange = parsed.targetRange,
                isPassed = idx == 0,
                isActiveSegmentStart = idx == 0,
                isNextPoint = idx == 1,
            )
        }

        val activePoint = points[0]
        val nextPoint = points[1]
        val target = activePoint.targetRange
        val gauge = RoutePacingEngine.evaluateGauge(
            currentPowerWatts = currentPower,
            isPowerAvailable = true,
            target = target,
            yellowMarginMinWatts = settings.yellowMarginMinWatts,
        )

        return GfPacingSnapshot(
            routeName = "GRAN FONDO 7 LAGOS 2026",
            hasActiveRoute = true,
            isOffRoute = false,
            isDemoMode = true,
            rideState = RideRecordingState.RECORDING,
            riderDistanceAlongRouteM = riderDistM,
            totalRouteDistanceM = totalDistM,
            currentPowerWatts = currentPower,
            isPowerAvailable = true,
            heartRateBpm = 152,
            isHeartRateAvailable = true,
            cadenceRpm = 86,
            isCadenceAvailable = true,
            lapAvgPowerWatts = 194,
            isLapAvgPowerAvailable = true,
            heartRateZones = DEFAULT_DEMO_HR_ZONES,
            powerZones = DEFAULT_DEMO_POWER_ZONES,
            zoneColorBackgrounds = settings.zoneColorBackgrounds,
            activeSegmentPoint = activePoint,
            currentTarget = target,
            gaugeZone = gauge.zone,
            deviationStatus = gauge.status,
            needleAngleDeg = gauge.needleAngleDeg,
            nextPoint = nextPoint,
            isNextPointApproaching = false,
            allPoints = points,
            isSegmentOverlayOpen = isSegmentOverlayOpen,
            overlayRemainingSeconds = overlayRemainingSeconds,
            isRecentSegmentChange = false,
            isMetricDistance = true,
            themePreference = settings.themePreference,
            isCueSheetPlusInstalled = isCueSheetPlusInstalled,
            tapActionPreference = settings.tapActionPreference,
            strings = strings,
        )
    }
}
