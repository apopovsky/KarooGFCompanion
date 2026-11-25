package com.karoogfcompanion.domain

/**
 * Geographic coordinate in decimal degrees.
 */
data class LatLngPoint(
    val lat: Double,
    val lng: Double,
)

/**
 * Sample along the route elevation profile.
 */
data class ElevationSample(
    val distanceM: Double,
    val elevationM: Double,
)

/**
 * Raw POI input coming either from Karoo OS navigation state or a parsed GPX file.
 */
data class RawRoutePoi(
    val id: String,
    val lat: Double,
    val lng: Double,
    val type: String,
    val name: String?,
    val description: String? = null,
    val distancesAlongRouteM: List<Double> = emptyList(),
)

/**
 * Planned power target range in watts.
 */
data class PowerTargetRange(
    val minWatts: Int,
    val maxWatts: Int,
    val nominalWatts: Int? = null,
) {
    val widthWatts: Int get() = (maxWatts - minWatts).coerceAtLeast(1)

    fun formatDisplay(): String = "$minWatts – $maxWatts W"

    fun formatCompact(): String = "$minWatts-$maxWatts W"
}

enum class GaugeZone {
    LOW_RED,
    LOW_YELLOW,
    IN_TARGET,
    HIGH_YELLOW,
    HIGH_RED,
    NO_TARGET,
}

enum class PowerDeviationStatus {
    WITHIN_TARGET,
    LOW,
    HIGH,
    NO_TARGET,
    NO_POWER,
}

enum class RideRecordingState {
    RECORDING,
    PAUSED,
    IDLE,
}

enum class PowerSmoothingMode(val seconds: Int) {
    INSTANT_1S(1),
    SMOOTHED_3S(3),
    SMOOTHED_5S(5),
    SMOOTHED_10S(10),
}

enum class ThemePreference {
    AUTO,
    LIGHT,
    DARK,
}

enum class TapActionPreference {
    OPEN_CUESHEET_PLUS,
    OPEN_CUESHEET_LIST,
    IN_FIELD_SEGMENT_LIST,
    NONE,
}

/**
 * Route waypoint resolved onto route progress with optional parsed power target.
 */
data class RouteWaypointPoint(
    val id: String,
    val rawName: String,
    val cleanName: String,
    val description: String?,
    val distanceAlongRouteM: Double,
    val remainingDistanceM: Double,
    val targetRange: PowerTargetRange?,
    val isPassed: Boolean,
    val isActiveSegmentStart: Boolean,
    val isNextPoint: Boolean,
)

/**
 * Inclusive min..max zone boundary mirrored from Karoo UserProfile (HR or Power).
 */
data class ZoneBound(
    val min: Int,
    val max: Int,
)

/**
 * User-configurable extension settings persisted in SharedPreferences.
 */
data class GfCompanionSettings(
    val powerSmoothingMode: PowerSmoothingMode = PowerSmoothingMode.SMOOTHED_3S,
    val singleTargetToleranceWatts: Int = 10,
    val yellowMarginMinWatts: Int = 15,
    val approachingDistanceMeters: Int = 500,
    val segmentAlertSound: Boolean = true,
    val segmentAlertPopup: Boolean = false,
    val zoneColorBackgrounds: Boolean = true,
    val themePreference: ThemePreference = ThemePreference.AUTO,
    val tapActionPreference: TapActionPreference = TapActionPreference.OPEN_CUESHEET_PLUS,
    val autoReturnSeconds: Int = 10,
)

/**
 * Immutable UI state snapshot rendered into the Karoo RemoteViews canvas or simulator.
 */
data class GfPacingSnapshot(
    val routeName: String? = null,
    val hasActiveRoute: Boolean = false,
    val isOffRoute: Boolean = false,
    val isDemoMode: Boolean = false,
    val rideState: RideRecordingState = RideRecordingState.RECORDING,
    val riderDistanceAlongRouteM: Double = 0.0,
    val totalRouteDistanceM: Double = 0.0,
    val currentPowerWatts: Int? = null,
    val isPowerAvailable: Boolean = false,
    val heartRateBpm: Int? = null,
    val isHeartRateAvailable: Boolean = false,
    val cadenceRpm: Int? = null,
    val isCadenceAvailable: Boolean = false,
    val lapAvgPowerWatts: Int? = null,
    val isLapAvgPowerAvailable: Boolean = false,
    val heartRateZones: List<ZoneBound> = emptyList(),
    val powerZones: List<ZoneBound> = emptyList(),
    val zoneColorBackgrounds: Boolean = true,
    val activeSegmentPoint: RouteWaypointPoint? = null,
    val currentTarget: PowerTargetRange? = null,
    val gaugeZone: GaugeZone = GaugeZone.NO_TARGET,
    val deviationStatus: PowerDeviationStatus = PowerDeviationStatus.NO_TARGET,
    val needleAngleDeg: Float? = null,
    val nextPoint: RouteWaypointPoint? = null,
    val isNextPointApproaching: Boolean = false,
    val allPoints: List<RouteWaypointPoint> = emptyList(),
    val isSegmentOverlayOpen: Boolean = false,
    val overlayRemainingSeconds: Int = 0,
    val isRecentSegmentChange: Boolean = false,
    val isMetricDistance: Boolean = true,
    val themePreference: ThemePreference = ThemePreference.AUTO,
    val isSystemNightMode: Boolean = false,
    val isCueSheetPlusInstalled: Boolean = false,
    val tapActionPreference: TapActionPreference = TapActionPreference.OPEN_CUESHEET_PLUS,
    val strings: GfStrings = GfStrings.forLocale(),
)
