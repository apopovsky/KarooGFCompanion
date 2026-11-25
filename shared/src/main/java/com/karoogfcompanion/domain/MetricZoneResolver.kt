package com.karoogfcompanion.domain

/**
 * Resolves Hammerhead Karoo standard zone background colors for HR and Power (5- or 7-zone
 * profiles from UserProfile) and a 3-color cadence guidance model (80-95 rpm optimal green).
 */
object MetricZoneResolver {

    data class ZoneVisualStyle(
        val bgColor: Int,
        val useDarkContent: Boolean,
    )

    // Hammerhead standard zone colors matching Dashboard & Karoo OS native data fields
    val ZONE_1_MINT = ZoneVisualStyle(0xFF7CF0B0.toInt(), useDarkContent = true)
    val ZONE_2_GREEN = ZoneVisualStyle(0xFF43B97F.toInt(), useDarkContent = true)
    val ZONE_3_YELLOW = ZoneVisualStyle(0xFFF6DF3F.toInt(), useDarkContent = true)
    val ZONE_4_CORAL = ZoneVisualStyle(0xFFEE8B62.toInt(), useDarkContent = true)
    val ZONE_5_ORANGE_RED = ZoneVisualStyle(0xFFEC542E.toInt(), useDarkContent = false)
    val ZONE_6_RED = ZoneVisualStyle(0xFFD62828.toInt(), useDarkContent = false)
    val ZONE_7_PURPLE = ZoneVisualStyle(0xFF9C1FB3.toInt(), useDarkContent = false)

    private val PALETTE_7_ZONES = listOf(
        ZONE_1_MINT,
        ZONE_2_GREEN,
        ZONE_3_YELLOW,
        ZONE_4_CORAL,
        ZONE_5_ORANGE_RED,
        ZONE_6_RED,
        ZONE_7_PURPLE,
    )

    private val PALETTE_5_ZONES = listOf(
        ZONE_1_MINT,
        ZONE_2_GREEN,
        ZONE_3_YELLOW,
        ZONE_4_CORAL,
        ZONE_6_RED,
    )

    const val CADENCE_GREEN_MIN = 80
    const val CADENCE_GREEN_MAX = 95
    const val CADENCE_YELLOW_LOW_MIN = 70
    const val CADENCE_YELLOW_HIGH_MAX = 105

    /**
     * Returns 1-based zone index in [1..zones.size], or null if [zones] is empty.
     */
    fun resolveZoneIndex(value: Int, zones: List<ZoneBound>): Int? {
        if (zones.isEmpty()) return null

        val exactIdx = zones.indexOfFirst { value in it.min..it.max }
        if (exactIdx >= 0) return exactIdx + 1

        if (value < zones.first().min) return 1
        if (value > zones.last().max) return zones.size

        val nextIdx = zones.indexOfFirst { value <= it.max }
        return if (nextIdx >= 0) nextIdx + 1 else zones.size
    }

    fun resolveHrOrPowerZoneStyle(
        value: Int?,
        isAvailable: Boolean,
        zones: List<ZoneBound>,
    ): ZoneVisualStyle? {
        if (!isAvailable || value == null || value <= 0 || zones.isEmpty()) return null
        val zoneIdx = resolveZoneIndex(value, zones) ?: return null
        val palette = if (zones.size <= 5) PALETTE_5_ZONES else PALETTE_7_ZONES
        return palette[(zoneIdx - 1).coerceIn(0, palette.lastIndex)]
    }

    /**
     * 3-color cadence model:
     * - 0 rpm (coasting/stopped) or unavailable: null (neutral background)
     * - 80..95 rpm: Green (recommended optimal band)
     * - 70..79 or 96..105 rpm: Yellow (moderately low/high)
     * - < 70 or > 105 rpm: Red (very low/high)
     */
    fun resolveCadenceStyle(
        cadenceRpm: Int?,
        isAvailable: Boolean,
    ): ZoneVisualStyle? {
        if (!isAvailable || cadenceRpm == null || cadenceRpm <= 0) return null
        return when {
            cadenceRpm in CADENCE_GREEN_MIN..CADENCE_GREEN_MAX -> ZONE_2_GREEN
            cadenceRpm in CADENCE_YELLOW_LOW_MIN until CADENCE_GREEN_MIN -> ZONE_3_YELLOW
            cadenceRpm in (CADENCE_GREEN_MAX + 1)..CADENCE_YELLOW_HIGH_MAX -> ZONE_3_YELLOW
            else -> ZONE_6_RED
        }
    }
}
