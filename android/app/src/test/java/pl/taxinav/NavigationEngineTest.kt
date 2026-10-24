package pl.taxinav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTest {

    // Prosta trasa: 1 km na północ, potem skręt w prawo i 500 m na wschód.
    private val lat0 = 52.2000
    private val lon0 = 21.0000
    private val dLat1km = 1000.0 / 111195.0
    private val dLon500m = 500.0 / (111195.0 * Math.cos(Math.toRadians(lat0 + dLat1km)))

    private val points = buildList {
        for (i in 0..10) add(LatLon(lat0 + dLat1km * i / 10, lon0))           // idx 0..10
        for (i in 1..5) add(LatLon(lat0 + dLat1km, lon0 + dLon500m * i / 5)) // idx 11..15
    }

    private val route = Route(
        points = points,
        maneuvers = listOf(
            Maneuver(1, "Jedź na północ ulicą Marszałkowską.", null, "Jedź na północ ulicą Marszałkowską.", 0, 1000, listOf("Marszałkowska")),
            Maneuver(10, "Skręć w prawo w Aleje Jerozolimskie.", "Skręć w prawo w Aleje Jerozolimskie.", "Skręć w prawo w Aleje Jerozolimskie.", 10, 500, listOf("Aleje Jerozolimskie")),
            Maneuver(4, "Dotarłeś do celu.", null, "Dotarłeś do celu.", 15, 0, emptyList()),
        ),
        distanceM = 1500, baseTimeS = 180, trafficTimeS = 240, hasTraffic = true, traffic = emptyList(), kinds = listOf("fastest"),
    )

    private fun at(metersNorth: Double, metersEast: Double = 0.0) =
        LatLon(lat0 + metersNorth / 111195.0, lon0 + metersEast / (111195.0 * Math.cos(Math.toRadians(lat0))))

    @Test fun polylineDecode() {
        // Przykład z dokumentacji Google (precyzja 5)
        val p = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, p.size)
        assertEquals(38.5, p[0].lat, 1e-6); assertEquals(-120.2, p[0].lon, 1e-6)
        assertEquals(43.252, p[2].lat, 1e-6); assertEquals(-126.453, p[2].lon, 1e-6)
    }

    @Test fun progressAndAnnouncements() {
        val said = ArrayList<String>()
        val e = NavigationEngine(route, { said += it })

        val s1 = e.update(at(100.0), speedMps = 10.0)
        assertFalse(s1.offRoute)
        assertEquals(10, s1.nextManeuver!!.type)
        assertEquals(900.0, s1.distanceToNextM, 15.0)
        assertEquals(1400.0, s1.remainingM, 20.0)
        assertEquals(240.0 * 1400 / 1500, s1.remainingS, 5.0)
        assertEquals(listOf("Jedź na północ ulicą Marszałkowską."), said)

        e.update(at(630.0), speedMps = 10.0)        // ok. 370 m przed skrętem -> zapowiedź
        assertEquals("Za 400 metrów, skręć w prawo w Aleje Jerozolimskie.", said.last())
        assertTrue(said.last().startsWith("Za "))
        assertTrue(said.last().endsWith("skręć w prawo w Aleje Jerozolimskie."))
        val count = said.size
        e.update(at(700.0), speedMps = 10.0)        // brak powtórzenia
        assertEquals(count, said.size)

        e.update(at(950.0), speedMps = 10.0)        // 50 m -> "Skręć w prawo..."
        assertEquals("Skręć w prawo w Aleje Jerozolimskie.", said.last())

        val s4 = e.update(at(1000.0, 200.0), speedMps = 10.0) // już po skręcie
        assertEquals(4, s4.nextManeuver!!.type)
        assertEquals(300.0, s4.distanceToNextM, 20.0)

        val s5 = e.update(at(1000.0, 490.0), speedMps = 5.0)
        assertTrue(s5.arrived)
        assertEquals("Dotarłeś do celu.", said.last())
    }

    @Test fun offRouteNeedsThreeFixes() {
        val e = NavigationEngine(route, {}, announceStart = false)
        assertFalse(e.update(at(300.0, 150.0)).offRoute)
        assertFalse(e.update(at(310.0, 150.0)).offRoute)
        assertTrue(e.update(at(320.0, 150.0)).offRoute)
        assertFalse(e.update(at(330.0, 5.0)).offRoute)   // wrócił na trasę
    }

    @Test fun polishDistances() {
        assertEquals("Za 50 metrów", Speech.inDistance(40.0))
        assertEquals("Za 300 metrów", Speech.inDistance(290.0))
        assertEquals("Za 800 metrów", Speech.inDistance(780.0))
        assertEquals("Za 1 kilometr", Speech.inDistance(980.0))
        assertEquals("Za półtora kilometra", Speech.inDistance(1450.0))
        assertEquals("Za 2 kilometry", Speech.inDistance(2100.0))
        assertEquals("Za 2,5 kilometra", Speech.inDistance(2600.0))
        assertEquals("Za 5 kilometrów", Speech.inDistance(5000.0))
        assertEquals("Za 12 kilometrów", Speech.inDistance(12000.0))
        assertEquals("Za 22 kilometry", Speech.inDistance(22000.0))
        assertEquals("350 m", Speech.short(347.0))
        assertEquals("1,2 km", Speech.short(1234.0))
    }
}
