package com.karoogfcompanion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePacingEngineTest {

    private fun buildLinearPolyline(totalMeters: Double = 30_000.0): RouteGeometryEngine.IndexedPolyline {
        val points = listOf(
            LatLngPoint(-40.157, -71.353),
            LatLngPoint(-40.157, -71.353 + (totalMeters / 85_000.0)),
        )
        return RouteGeometryEngine.buildIndexedPolyline(points, declaredRouteDistanceM = totalMeters)
    }

    @Test
    fun startOfRouteSnapsStartSegmentAndOrdinaryPointPreservesNullTarget() {
        val poly = buildLinearPolyline(30_000.0)
        val pois = listOf(
            RawRoutePoi("p0", 0.0, 0.0, "Generic", "Largada a Chapelco (190-205W)", distancesAlongRouteM = listOf(0.0)),
            RawRoutePoi("p1", 0.0, 0.0, "Water", "Puesto Agua", distancesAlongRouteM = listOf(8_000.0)),
            RawRoutePoi("p2", 0.0, 0.0, "Summit", "Puerto Chapelco (175-190W)", distancesAlongRouteM = listOf(14_500.0)),
        )

        // At km 0: Largada is active, nextPoint is the ordinary "Puesto Agua" with null target
        val atStart = RoutePacingEngine.resolveRoutePacing(poly, pois, riderDistanceAlongRouteM = 0.0)
        assertEquals("Largada a Chapelco", atStart.activeSegmentPoint?.cleanName)
        assertEquals(PowerTargetRange(190, 205), atStart.activeSegmentPoint?.targetRange)
        assertEquals("Puesto Agua", atStart.nextPoint?.cleanName)
        assertNull(atStart.nextPoint?.targetRange)
        assertEquals(8_000.0, atStart.nextPoint?.remainingDistanceM ?: -1.0, 0.1)

        // At km 9 (past ordinary Puesto Agua): Largada target STILL remains active until Puerto Chapelco!
        val afterOrdinary = RoutePacingEngine.resolveRoutePacing(poly, pois, riderDistanceAlongRouteM = 9_000.0)
        assertEquals("Largada a Chapelco", afterOrdinary.activeSegmentPoint?.cleanName)
        assertEquals(PowerTargetRange(190, 205), afterOrdinary.activeSegmentPoint?.targetRange)
        assertEquals("Puerto Chapelco", afterOrdinary.nextPoint?.cleanName)
        assertEquals(PowerTargetRange(175, 190), afterOrdinary.nextPoint?.targetRange)
        assertEquals(5_500.0, afterOrdinary.nextPoint?.remainingDistanceM ?: -1.0, 0.1)
    }

    @Test
    fun noTargetBeforeFirstPowerPointWhenFarFromStart() {
        val poly = buildLinearPolyline(20_000.0)
        val pois = listOf(
            RawRoutePoi("p0", 0.0, 0.0, "Water", "Salida Neutral", distancesAlongRouteM = listOf(2_000.0)),
            RawRoutePoi("p1", 0.0, 0.0, "Summit", "Puerto 1 (200-220W)", distancesAlongRouteM = listOf(5_000.0)),
        )

        val beforeFirstPower = RoutePacingEngine.resolveRoutePacing(poly, pois, riderDistanceAlongRouteM = 1_000.0)
        assertNull(beforeFirstPower.activeSegmentPoint)
        assertEquals("Salida Neutral", beforeFirstPower.nextPoint?.cleanName)
        assertNull(beforeFirstPower.nextPoint?.targetRange)

        val gauge = RoutePacingEngine.evaluateGauge(
            currentPowerWatts = 210,
            isPowerAvailable = true,
            target = beforeFirstPower.activeSegmentPoint?.targetRange,
        )
        assertEquals(GaugeZone.NO_TARGET, gauge.zone)
        assertEquals(PowerDeviationStatus.NO_TARGET, gauge.status)
        assertNull(gauge.needleAngleDeg)
    }

    @Test
    fun overlappingPointsPreferLaterPointAndApproachingTriggersWithinThreshold() {
        val poly = buildLinearPolyline(20_000.0)
        val pois = listOf(
            RawRoutePoi("p0", 0.0, 0.0, "Generic", "First (180-190W)", distancesAlongRouteM = listOf(5_000.0)),
            RawRoutePoi("p1", 0.0, 0.0, "Generic", "Second (210-225W)", distancesAlongRouteM = listOf(5_008.0)),
            RawRoutePoi("p2", 0.0, 0.0, "Summit", "Final Climb (160-175W)", distancesAlongRouteM = listOf(10_000.0)),
        )

        val resolved = RoutePacingEngine.resolveRoutePacing(
            indexedPolyline = poly,
            routePois = pois,
            riderDistanceAlongRouteM = 9_650.0,
            approachingDistanceMeters = 500,
        )
        assertEquals("Second", resolved.activeSegmentPoint?.cleanName)
        assertEquals(PowerTargetRange(210, 225), resolved.activeSegmentPoint?.targetRange)
        assertEquals("Final Climb", resolved.nextPoint?.cleanName)
        assertTrue(resolved.isNextPointApproaching)

        val finished = RoutePacingEngine.resolveRoutePacing(
            indexedPolyline = poly,
            routePois = pois,
            riderDistanceAlongRouteM = 10_500.0,
        )
        assertEquals("Final Climb", finished.activeSegmentPoint?.cleanName)
        assertNull(finished.nextPoint)
        assertFalse(finished.isNextPointApproaching)
    }

    @Test
    fun gaugeBoundariesAndStalePowerConsistentAcrossAllZones() {
        val target = PowerTargetRange(190, 205)

        val atZero = RoutePacingEngine.evaluateGauge(0, isPowerAvailable = true, target = target)
        assertEquals(GaugeZone.LOW_RED, atZero.zone)
        assertEquals(PowerDeviationStatus.LOW, atZero.status)
        assertTrue((atZero.needleAngleDeg ?: 999f) < RoutePacingEngine.ANGLE_RED_LOW_END_DEG)

        val belowMin = RoutePacingEngine.evaluateGauge(189, isPowerAvailable = true, target = target)
        assertEquals(GaugeZone.LOW_YELLOW, belowMin.zone)
        assertEquals(PowerDeviationStatus.LOW, belowMin.status)
        assertTrue((belowMin.needleAngleDeg ?: 999f) < RoutePacingEngine.ANGLE_GREEN_START_DEG)

        val atMin = RoutePacingEngine.evaluateGauge(190, isPowerAvailable = true, target = target)
        assertEquals(GaugeZone.IN_TARGET, atMin.zone)
        assertEquals(PowerDeviationStatus.WITHIN_TARGET, atMin.status)
        assertEquals(RoutePacingEngine.ANGLE_GREEN_START_DEG, atMin.needleAngleDeg ?: -1f, 0.01f)

        val atMax = RoutePacingEngine.evaluateGauge(205, isPowerAvailable = true, target = target)
        assertEquals(GaugeZone.IN_TARGET, atMax.zone)
        assertEquals(PowerDeviationStatus.WITHIN_TARGET, atMax.status)
        assertEquals(RoutePacingEngine.ANGLE_GREEN_END_DEG, atMax.needleAngleDeg ?: -1f, 0.01f)

        val aboveMax = RoutePacingEngine.evaluateGauge(206, isPowerAvailable = true, target = target)
        assertEquals(GaugeZone.HIGH_YELLOW, aboveMax.zone)
        assertEquals(PowerDeviationStatus.HIGH, aboveMax.status)
        assertTrue((aboveMax.needleAngleDeg ?: 0f) > RoutePacingEngine.ANGLE_GREEN_END_DEG)

        val wayAboveMax = RoutePacingEngine.evaluateGauge(280, isPowerAvailable = true, target = target)
        assertEquals(GaugeZone.HIGH_RED, wayAboveMax.zone)
        assertEquals(PowerDeviationStatus.HIGH, wayAboveMax.status)
        assertTrue((wayAboveMax.needleAngleDeg ?: 0f) > RoutePacingEngine.ANGLE_YELLOW_HIGH_END_DEG)

        val stale = RoutePacingEngine.evaluateGauge(null, isPowerAvailable = false, target = target)
        assertEquals(PowerDeviationStatus.NO_POWER, stale.status)
        assertNull(stale.needleAngleDeg)
        assertNotNull(target)
    }

    @Test
    fun stepPowerAccumulatorComputesMeanAndResetsOnPoiTransition() {
        val acc = StepPowerAccumulator()
        acc.updateSegment("seg_1")

        assertEquals(120, acc.onPowerSample(120, isPowerAvailable = true, isRecording = true, nowMs = 1_000L))
        assertEquals(130, acc.onPowerSample(140, isPowerAvailable = true, isRecording = true, nowMs = 2_000L))
        assertEquals(137, acc.onPowerSample(150, isPowerAvailable = true, isRecording = true, nowMs = 3_000L))

        // While paused, step average remains frozen even if 0 W arrives
        assertEquals(137, acc.onPowerSample(0, isPowerAvailable = true, isRecording = false, nowMs = 4_000L))
        assertEquals(137, acc.currentStepAverageWatts)

        // Transitioning past a power POI resets the step average to null and starts fresh
        assertTrue(acc.updateSegment("seg_2"))
        assertNull(acc.currentStepAverageWatts)
        assertEquals(200, acc.onPowerSample(200, isPowerAvailable = true, isRecording = true, nowMs = 5_000L))
    }
}
