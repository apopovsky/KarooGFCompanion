package com.karoogfcompanion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class GpxRouteParserTest {

    @Test
    fun parsesGpxWithPowerBearingAndOrdinaryWaypoints() {
        val gpxXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test">
              <metadata><name>Test 7 Lagos</name></metadata>
              <wpt lat="-40.1576" lon="-71.3530">
                <ele>651.0</ele>
                <name>Largada a Chapelco (190-205W)</name>
                <desc>Km 0.0 a 14.5</desc>
                <type>Generic</type>
              </wpt>
              <wpt lat="-40.1576" lon="-71.3100">
                <ele>800.0</ele>
                <name>Mirador Intermedio</name>
                <type>Viewpoint</type>
              </wpt>
              <wpt lat="-40.1576" lon="-71.2600">
                <ele>1115.6</ele>
                <name>Puerto Chapelco (175-190W)</name>
                <type>Summit</type>
              </wpt>
              <trk>
                <name>Track 1</name>
                <trkseg>
                  <trkpt lat="-40.1576" lon="-71.3530"><ele>651.0</ele></trkpt>
                  <trkpt lat="-40.1576" lon="-71.3100"><ele>800.0</ele></trkpt>
                  <trkpt lat="-40.1576" lon="-71.2600"><ele>1115.6</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val parsed = GpxRouteParser.parse(ByteArrayInputStream(gpxXml.toByteArray(Charsets.UTF_8)))
        assertEquals("Test 7 Lagos", parsed.routeName)
        assertEquals(3, parsed.routePois.size)
        assertTrue(parsed.totalDistanceM > 7_000.0)

        val resolved = RoutePacingEngine.resolveRoutePacing(
            indexedPolyline = parsed.indexedPolyline,
            routePois = parsed.routePois,
            riderDistanceAlongRouteM = 500.0,
        )
        assertEquals("Largada a Chapelco", resolved.activeSegmentPoint?.cleanName)
        assertEquals(PowerTargetRange(190, 205), resolved.activeSegmentPoint?.targetRange)
        assertEquals("Mirador Intermedio", resolved.nextPoint?.cleanName)
        assertNull(resolved.nextPoint?.targetRange)
    }
}
