package com.karoogfcompanion.domain

import kotlin.math.roundToInt

/**
 * Computes the time-weighted average power for the current route pacing segment (step)
 * and resets whenever the rider transitions into a new power-bearing POI segment.
 */
class StepPowerAccumulator {

    private var currentSegmentKey: String? = null
    private var weightedWattSeconds: Double = 0.0
    private var accumulatedSeconds: Double = 0.0
    private var lastSampleTimestampMs: Long = -1L
    private var lastAverageWatts: Int? = null

    val currentStepAverageWatts: Int?
        @Synchronized get() = lastAverageWatts

    /**
     * Switches the tracked segment key. Resets accumulated step average when the segment changes.
     * @return true if a segment transition reset occurred.
     */
    @Synchronized
    fun updateSegment(segmentKey: String?): Boolean {
        if (segmentKey == currentSegmentKey) return false
        currentSegmentKey = segmentKey
        resetAccumulatedValues()
        return true
    }

    /**
     * Records a power sample for the active step, freezing when paused or when power is unavailable.
     */
    @Synchronized
    fun onPowerSample(
        powerWatts: Int?,
        isPowerAvailable: Boolean,
        isRecording: Boolean,
        nowMs: Long,
    ): Int? {
        if (!isRecording || !isPowerAvailable || powerWatts == null) {
            lastSampleTimestampMs = -1L
            return lastAverageWatts
        }

        val p = powerWatts.coerceAtLeast(0).toDouble()
        if (accumulatedSeconds <= 0.0) {
            weightedWattSeconds = p
            accumulatedSeconds = 1.0
            lastSampleTimestampMs = nowMs
            lastAverageWatts = p.roundToInt()
            return lastAverageWatts
        }

        if (lastSampleTimestampMs < 0L) {
            // Resuming after pause or brief sensor dropout: add 1s weight without integrating pause duration
            weightedWattSeconds += p
            accumulatedSeconds += 1.0
            lastSampleTimestampMs = nowMs
            lastAverageWatts = (weightedWattSeconds / accumulatedSeconds).roundToInt()
            return lastAverageWatts
        }

        if (nowMs <= lastSampleTimestampMs) {
            return lastAverageWatts
        }

        val dtSec = ((nowMs - lastSampleTimestampMs) / 1000.0).coerceIn(0.1, 10.0)
        weightedWattSeconds += p * dtSec
        accumulatedSeconds += dtSec
        lastSampleTimestampMs = nowMs
        lastAverageWatts = (weightedWattSeconds / accumulatedSeconds).roundToInt()
        return lastAverageWatts
    }

    @Synchronized
    fun reset() {
        currentSegmentKey = null
        resetAccumulatedValues()
    }

    private fun resetAccumulatedValues() {
        weightedWattSeconds = 0.0
        accumulatedSeconds = 0.0
        lastSampleTimestampMs = -1L
        lastAverageWatts = null
    }
}
