package com.karoogfcompanion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricZoneResolverTest {

    private val sevenPowerZones = listOf(
        ZoneBound(0, 130),
        ZoneBound(131, 177),
        ZoneBound(178, 213),
        ZoneBound(214, 248),
        ZoneBound(249, 284),
        ZoneBound(285, 355),
        ZoneBound(356, 2000),
    )

    private val fiveHrZones = listOf(
        ZoneBound(0, 120),
        ZoneBound(121, 140),
        ZoneBound(141, 158),
        ZoneBound(159, 172),
        ZoneBound(173, 210),
    )

    @Test
    fun resolveSevenZones_mapsAllZonesAndBoundaries() {
        assertEquals(MetricZoneResolver.ZONE_1_MINT, MetricZoneResolver.resolveHrOrPowerZoneStyle(110, true, sevenPowerZones))
        assertEquals(MetricZoneResolver.ZONE_2_GREEN, MetricZoneResolver.resolveHrOrPowerZoneStyle(177, true, sevenPowerZones))
        assertEquals(MetricZoneResolver.ZONE_3_YELLOW, MetricZoneResolver.resolveHrOrPowerZoneStyle(194, true, sevenPowerZones))
        assertEquals(MetricZoneResolver.ZONE_4_CORAL, MetricZoneResolver.resolveHrOrPowerZoneStyle(230, true, sevenPowerZones))
        assertEquals(MetricZoneResolver.ZONE_5_ORANGE_RED, MetricZoneResolver.resolveHrOrPowerZoneStyle(260, true, sevenPowerZones))
        assertEquals(MetricZoneResolver.ZONE_6_RED, MetricZoneResolver.resolveHrOrPowerZoneStyle(310, true, sevenPowerZones))
        assertEquals(MetricZoneResolver.ZONE_7_PURPLE, MetricZoneResolver.resolveHrOrPowerZoneStyle(420, true, sevenPowerZones))
        assertEquals(MetricZoneResolver.ZONE_7_PURPLE, MetricZoneResolver.resolveHrOrPowerZoneStyle(2500, true, sevenPowerZones))
    }

    @Test
    fun resolveFiveZones_usesFiveZoneRedForTopZone() {
        assertEquals(MetricZoneResolver.ZONE_1_MINT, MetricZoneResolver.resolveHrOrPowerZoneStyle(105, true, fiveHrZones))
        assertEquals(MetricZoneResolver.ZONE_4_CORAL, MetricZoneResolver.resolveHrOrPowerZoneStyle(165, true, fiveHrZones))
        assertEquals(MetricZoneResolver.ZONE_6_RED, MetricZoneResolver.resolveHrOrPowerZoneStyle(180, true, fiveHrZones))
    }

    @Test
    fun resolveHrOrPower_returnsNullWhenUnavailableZeroOrEmptyZones() {
        assertNull(MetricZoneResolver.resolveHrOrPowerZoneStyle(150, false, sevenPowerZones))
        assertNull(MetricZoneResolver.resolveHrOrPowerZoneStyle(null, true, sevenPowerZones))
        assertNull(MetricZoneResolver.resolveHrOrPowerZoneStyle(0, true, sevenPowerZones))
        assertNull(MetricZoneResolver.resolveHrOrPowerZoneStyle(150, true, emptyList()))
    }

    @Test
    fun resolveCadence_threeColorBandsAndZeroNeutral() {
        assertNull(MetricZoneResolver.resolveCadenceStyle(0, true))
        assertNull(MetricZoneResolver.resolveCadenceStyle(85, false))
        assertNull(MetricZoneResolver.resolveCadenceStyle(null, true))

        assertEquals(MetricZoneResolver.ZONE_6_RED, MetricZoneResolver.resolveCadenceStyle(65, true))
        assertEquals(MetricZoneResolver.ZONE_6_RED, MetricZoneResolver.resolveCadenceStyle(69, true))

        assertEquals(MetricZoneResolver.ZONE_3_YELLOW, MetricZoneResolver.resolveCadenceStyle(70, true))
        assertEquals(MetricZoneResolver.ZONE_3_YELLOW, MetricZoneResolver.resolveCadenceStyle(79, true))

        assertEquals(MetricZoneResolver.ZONE_2_GREEN, MetricZoneResolver.resolveCadenceStyle(80, true))
        assertEquals(MetricZoneResolver.ZONE_2_GREEN, MetricZoneResolver.resolveCadenceStyle(86, true))
        assertEquals(MetricZoneResolver.ZONE_2_GREEN, MetricZoneResolver.resolveCadenceStyle(95, true))

        assertEquals(MetricZoneResolver.ZONE_3_YELLOW, MetricZoneResolver.resolveCadenceStyle(96, true))
        assertEquals(MetricZoneResolver.ZONE_3_YELLOW, MetricZoneResolver.resolveCadenceStyle(105, true))

        assertEquals(MetricZoneResolver.ZONE_6_RED, MetricZoneResolver.resolveCadenceStyle(106, true))
        assertEquals(MetricZoneResolver.ZONE_6_RED, MetricZoneResolver.resolveCadenceStyle(120, true))
    }
}
