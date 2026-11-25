package com.karoogfcompanion.domain

/**
 * Result of parsing a route waypoint name for an embedded target power range or single watt target.
 */
data class ParsedWaypointTarget(
    val cleanName: String,
    val targetRange: PowerTargetRange?,
)

/**
 * Parses planned power targets from route waypoint names such as:
 * - "Largada a Chapelco (190-205W)"
 * - "Techo Ruta 1180m (120-150W)"
 * - "Puerto Chapelco [175 – 190 W]"
 * - "Steady Climb (200W)" -> expands using [singleTargetToleranceWatts]
 *
 * Numbers without an explicit watt unit (e.g., "1180m", "Km 14.5", "(1116m)") are never treated as power.
 */
object PowerTargetParser {

    const val MIN_VALID_WATTS = 30
    const val MAX_VALID_WATTS = 1500

    // Priority 1: Parenthesized or bracketed power range, e.g. (190-205W), [175 – 190 W], (190W-205W), (190..205 watts)
    private val ENCLOSED_RANGE_REGEX = Regex(
        """(?i)[\(\[]\s*(\d{2,4})\s*(?:w|watts?)?\s*(?:-|–|—|\.\.|a|to)\s*(\d{2,4})\s*(?:w|watts?)\s*[\)\]]"""
    )

    // Priority 2: Parenthesized or bracketed single power target, e.g. (200W), [200 watts]
    private val ENCLOSED_SINGLE_REGEX = Regex(
        """(?i)[\(\[]\s*(\d{2,4})\s*(?:w|watts?)\s*[\)\]]"""
    )

    // Priority 3: Unparenthesized trailing range at the end of a waypoint name, e.g. "Subida Falkner 190-205W"
    private val TRAILING_RANGE_REGEX = Regex(
        """(?i)(?:^|\s)(\d{2,4})\s*(?:w|watts?)?\s*(?:-|–|—|\.\.)\s*(\d{2,4})\s*(?:w|watts?)\s*$"""
    )

    // Priority 4: Unparenthesized trailing single watt target at the end of a waypoint name, e.g. "Subida Falkner 200W"
    private val TRAILING_SINGLE_REGEX = Regex(
        """(?i)(?:^|\s)(\d{2,4})\s*(?:w|watts?)\s*$"""
    )

    private val MULTI_SPACE_REGEX = Regex("""\s{2,}""")

    fun parse(
        rawName: String?,
        rawDescription: String? = null,
        singleTargetToleranceWatts: Int = 10,
    ): ParsedWaypointTarget {
        val original = rawName?.trim().orEmpty()
        if (original.isEmpty()) {
            return ParsedWaypointTarget(cleanName = "", targetRange = null)
        }

        // Preserve unused parameter explicitly in case callers inspect description alongside name in the future.
        @Suppress("UNUSED_VARIABLE")
        val unusedDesc = rawDescription

        ENCLOSED_RANGE_REGEX.findAll(original).lastOrNull()?.let { match ->
            val range = buildValidatedRange(
                first = match.groupValues[1].toIntOrNull(),
                second = match.groupValues[2].toIntOrNull(),
            )
            if (range != null) {
                return ParsedWaypointTarget(
                    cleanName = stripToken(original, match.range),
                    targetRange = range,
                )
            }
        }

        ENCLOSED_SINGLE_REGEX.findAll(original).lastOrNull()?.let { match ->
            val watts = match.groupValues[1].toIntOrNull()
            val range = buildSingleTargetRange(watts, singleTargetToleranceWatts)
            if (range != null) {
                return ParsedWaypointTarget(
                    cleanName = stripToken(original, match.range),
                    targetRange = range,
                )
            }
        }

        TRAILING_RANGE_REGEX.find(original)?.let { match ->
            val range = buildValidatedRange(
                first = match.groupValues[1].toIntOrNull(),
                second = match.groupValues[2].toIntOrNull(),
            )
            if (range != null) {
                return ParsedWaypointTarget(
                    cleanName = stripToken(original, match.range),
                    targetRange = range,
                )
            }
        }

        TRAILING_SINGLE_REGEX.find(original)?.let { match ->
            val watts = match.groupValues[1].toIntOrNull()
            val range = buildSingleTargetRange(watts, singleTargetToleranceWatts)
            if (range != null) {
                return ParsedWaypointTarget(
                    cleanName = stripToken(original, match.range),
                    targetRange = range,
                )
            }
        }

        return ParsedWaypointTarget(cleanName = original, targetRange = null)
    }

    private fun buildValidatedRange(first: Int?, second: Int?): PowerTargetRange? {
        if (first == null || second == null) return null
        if (first !in MIN_VALID_WATTS..MAX_VALID_WATTS || second !in MIN_VALID_WATTS..MAX_VALID_WATTS) {
            return null
        }
        val low = minOf(first, second)
        val high = maxOf(first, second)
        return PowerTargetRange(minWatts = low, maxWatts = high)
    }

    private fun buildSingleTargetRange(watts: Int?, toleranceWatts: Int): PowerTargetRange? {
        if (watts == null || watts !in MIN_VALID_WATTS..MAX_VALID_WATTS) return null
        val tol = toleranceWatts.coerceIn(0, 50)
        val low = (watts - tol).coerceAtLeast(MIN_VALID_WATTS)
        val high = (watts + tol).coerceAtMost(MAX_VALID_WATTS)
        return PowerTargetRange(minWatts = low, maxWatts = high, nominalWatts = watts)
    }

    private fun stripToken(original: String, tokenRange: IntRange): String {
        val stripped = (original.substring(0, tokenRange.first) + " " + original.substring(tokenRange.last + 1))
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
            .trimEnd('-', '–', ':', '|', '•')
            .trim()
        return stripped.ifEmpty { original }
    }
}
