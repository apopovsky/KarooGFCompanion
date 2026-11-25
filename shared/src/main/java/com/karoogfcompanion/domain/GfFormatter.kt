package com.karoogfcompanion.domain

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Formatting utilities for ride distances and target strings.
 */
object GfFormatter {

    data class FormattedValue(
        val value: String,
        val unit: String,
    ) {
        val combined: String get() = if (unit.isEmpty()) value else "$value $unit"
    }

    fun formatDistance(meters: Double, isMetric: Boolean): FormattedValue {
        val safeM = meters.coerceAtLeast(0.0)
        return if (isMetric) {
            if (safeM < 1000.0) {
                val roundedM = ((safeM / 10.0).roundToInt() * 10).coerceAtLeast(0)
                FormattedValue(value = roundedM.toString(), unit = "m")
            } else {
                val km = safeM / 1000.0
                if (km >= 100.0) {
                    FormattedValue(value = km.roundToInt().toString(), unit = "km")
                } else {
                    FormattedValue(value = String.format(Locale.US, "%.1f", km), unit = "km")
                }
            }
        } else {
            val miles = safeM / 1609.344
            if (miles < 0.25) {
                val feet = (safeM * 3.28084).roundToInt()
                val roundedFt = ((feet / 50.0).roundToInt() * 50).coerceAtLeast(0)
                FormattedValue(value = roundedFt.toString(), unit = "ft")
            } else if (miles >= 100.0) {
                FormattedValue(value = miles.roundToInt().toString(), unit = "mi")
            } else {
                FormattedValue(value = String.format(Locale.US, "%.1f", miles), unit = "mi")
            }
        }
    }
}
