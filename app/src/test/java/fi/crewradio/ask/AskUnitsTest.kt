package fi.crewradio.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signal K is SI and the crew is not. Every number the boat says aloud passes through here, so
 * these are table checks against values that can be worked out by hand.
 */
class AskUnitsTest {

    @Test
    fun headingIsWholeDegreesAndWrapsLikeACompass() {
        assertEquals(245, AskUnits.headingDegrees(4.2771))
        assertEquals(0, AskUnits.headingDegrees(0.0))
        assertEquals(0, AskUnits.headingDegrees(2 * Math.PI))          // 360 reads back as 0
        assertEquals(180, AskUnits.headingDegrees(Math.PI))
        assertEquals(270, AskUnits.headingDegrees(-Math.PI / 2))       // negative radians still bear
    }

    @Test
    fun windAngleKeepsTheSideItIsOn() {
        assertEquals(-90, AskUnits.relativeDegrees(-Math.PI / 2))      // port
        assertEquals(45, AskUnits.relativeDegrees(Math.PI / 4))        // starboard
        assertEquals(180, AskUnits.relativeDegrees(Math.PI))           // dead astern
        assertEquals(-135, AskUnits.relativeDegrees(1.25 * Math.PI))   // 225°: over the stern, back to port
        assertEquals(-90, AskUnits.relativeDegrees(1.5 * Math.PI))     // 270° is 90° to port
    }

    @Test
    fun speedIsOneDecimalInTheCrewsUnit() {
        assertEquals("6.2", AskUnits.speed(3.19, AskUnits.Speed.KNOTS))
        assertEquals("3.2", AskUnits.speed(3.19, AskUnits.Speed.METRES_PER_SECOND))
        assertEquals("11.5", AskUnits.speed(3.19, AskUnits.Speed.KM_PER_HOUR))
    }

    @Test
    fun depthConvertsToFeetWhenAsked() {
        assertEquals("4.2", AskUnits.depth(4.2, AskUnits.Depth.METRES))
        assertEquals("13.8", AskUnits.depth(4.2, AskUnits.Depth.FEET))
    }

    @Test
    fun aCloseDistanceIsMetresAndAFarOneIsMiles() {
        assertTrue(AskUnits.distanceIsClose(320.0))
        assertFalse(AskUnits.distanceIsClose(1852.0))
        assertEquals("320", AskUnits.distanceMetres(320.0))
        assertEquals("2.0", AskUnits.distanceNauticalMiles(3704.0))
    }

    @Test
    fun temperatureLeavesKelvinBehind() {
        assertEquals("18.0", AskUnits.celsius(291.15))
        assertEquals("-1.5", AskUnits.celsius(271.65))
    }

    @Test
    fun aLevelIsWholePercentAndCannotOverflow() {
        assertEquals(62, AskUnits.percent(0.618))
        assertEquals(100, AskUnits.percent(1.02))      // a gauge reading over full is full
        assertEquals(0, AskUnits.percent(-0.01))
    }

    @Test
    fun voltageKeepsTheDecimalThatMatters() {
        assertEquals("12.6", AskUnits.volts(12.61))
        assertEquals("12.2", AskUnits.volts(12.24))
    }

    @Test
    fun revolutionsComeFromHertzAndRoundToTen() {
        assertEquals("800", AskUnits.rpm(13.333))
        assertEquals("2200", AskUnits.rpm(36.66))
        assertEquals("0", AskUnits.rpm(0.0))
    }

    @Test
    fun litresComeFromCubicMetres() {
        assertEquals("120", AskUnits.litres(0.12))
    }

    @Test
    fun timeToGoIsWholeMinutes() {
        assertEquals(0L, AskUnits.minutes(29.0))
        assertEquals(1L, AskUnits.minutes(31.0))
        assertEquals(95L, AskUnits.minutes(5700.0))
    }

    @Test
    fun aPositionIsDegreesAndDecimalMinutes() {
        val latitude = AskUnits.coordinate(60.163333)
        assertEquals(60, latitude.degrees)
        assertEquals("09.8", latitude.minutes)          // padded: "nine point eight" would be misheard
        assertTrue(latitude.positive)

        val longitude = AskUnits.coordinate(-24.956667)
        assertEquals(24, longitude.degrees)
        assertEquals("57.4", longitude.minutes)
        assertFalse(longitude.positive)
    }

    @Test
    fun roundingTheMinutesCarriesIntoTheDegrees() {
        // 60.99999° is 60° 59.9994′, which reads as 60.0 minutes — and must become 61° 00.0′.
        val carried = AskUnits.coordinate(60.99999)
        assertEquals(61, carried.degrees)
        assertEquals("00.0", carried.minutes)
    }
}
