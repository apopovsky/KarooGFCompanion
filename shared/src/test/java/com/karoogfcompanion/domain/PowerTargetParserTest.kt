package com.karoogfcompanion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerTargetParserTest {

    @Test
    fun parsesAllSevenLagosWaypointsAccurately() {
        val cases = listOf(
            "Largada a Chapelco (190-205W)" to Triple("Largada a Chapelco", 190, 205),
            "Puerto Chapelco (175-190W)" to Triple("Puerto Chapelco", 175, 190),
            "Techo Ruta 1180m (120-150W)" to Triple("Techo Ruta 1180m", 120, 150),
            "Subida Machonico (195-210W)" to Triple("Subida Machonico", 195, 210),
            "Cima Machonico (145-170W)" to Triple("Cima Machonico", 145, 170),
            "Hidratacion 1 (125-155W)" to Triple("Hidratacion 1", 125, 155),
            "Subida Falkner (190-205W)" to Triple("Subida Falkner", 190, 205),
            "Cima Falkner (115-145W)" to Triple("Cima Falkner", 115, 145),
            "Sprint Oficial (155-180W)" to Triple("Sprint Oficial", 155, 180),
            "Pie Pto. Escondido (195-210W)" to Triple("Pie Pto. Escondido", 195, 210),
            "Hidratacion 2 (190-205W)" to Triple("Hidratacion 2", 190, 205),
            "Cima Escondido (125-155W)" to Triple("Cima Escondido", 125, 155),
            "Pie Pto. Ruca Malen (215-230W)" to Triple("Pie Pto. Ruca Malen", 215, 230),
            "Hidratacion 3 Cima (120-150W)" to Triple("Hidratacion 3 Cima", 120, 150),
            "Pie Muro 2 Espejo (215-230W)" to Triple("Pie Muro 2 Espejo", 215, 230),
            "Cima Muro 2 Espejo (125-155W)" to Triple("Cima Muro 2 Espejo", 125, 155),
            "Pie Muro 3 Corrent. (220-235W)" to Triple("Pie Muro 3 Corrent.", 220, 235),
            "Cima Muro 3 Corrent. (150-175W)" to Triple("Cima Muro 3 Corrent.", 150, 175),
            "Tramo Final VLA (215-235W)" to Triple("Tramo Final VLA", 215, 235),
        )

        for ((raw, expected) in cases) {
            val parsed = PowerTargetParser.parse(raw)
            assertEquals("Clean name mismatch for $raw", expected.first, parsed.cleanName)
            assertEquals("Min watts mismatch for $raw", expected.second, parsed.targetRange?.minWatts)
            assertEquals("Max watts mismatch for $raw", expected.third, parsed.targetRange?.maxWatts)
        }
    }

    @Test
    fun supportsSingleWattTargetsAndVariantDelimiters() {
        val single = PowerTargetParser.parse("Steady Climb (200W)", singleTargetToleranceWatts = 10)
        assertEquals("Steady Climb", single.cleanName)
        assertEquals(PowerTargetRange(190, 210, nominalWatts = 200), single.targetRange)

        val brackets = PowerTargetParser.parse("Puerto [175 – 190 W]")
        assertEquals("Puerto", brackets.cleanName)
        assertEquals(PowerTargetRange(175, 190), brackets.targetRange)

        val reversed = PowerTargetParser.parse("Sprint (210-185W)")
        assertEquals("Sprint", reversed.cleanName)
        assertEquals(PowerTargetRange(185, 210), reversed.targetRange)

        val trailing = PowerTargetParser.parse("Subida Falkner 190-205W")
        assertEquals("Subida Falkner", trailing.cleanName)
        assertEquals(PowerTargetRange(190, 205), trailing.targetRange)
    }

    @Test
    fun ignoresElevationNumbersAndMalformedOutOfBoundsTargets() {
        val elevOnly = PowerTargetParser.parse("Techo Ruta 1180m")
        assertEquals("Techo Ruta 1180m", elevOnly.cleanName)
        assertNull(elevOnly.targetRange)

        val parenElev = PowerTargetParser.parse("Puerto Chapelco (1116m)")
        assertEquals("Puerto Chapelco (1116m)", parenElev.cleanName)
        assertNull(parenElev.targetRange)

        val kmMark = PowerTargetParser.parse("Control Km 14.5")
        assertEquals("Control Km 14.5", kmMark.cleanName)
        assertNull(kmMark.targetRange)

        val tooLow = PowerTargetParser.parse("Downhill (15-25W)")
        assertNull(tooLow.targetRange)

        val tooHigh = PowerTargetParser.parse("Rocket (1600-1800W)")
        assertNull(tooHigh.targetRange)

        // Description prose must not fabricate a power target for an ordinary waypoint
        val ordinaryWithDesc = PowerTargetParser.parse(
            rawName = "Aid Station",
            rawDescription = "Bajar 15W y no pasar de 210W hasta el techo.",
        )
        assertEquals("Aid Station", ordinaryWithDesc.cleanName)
        assertNull(ordinaryWithDesc.targetRange)
    }
}
