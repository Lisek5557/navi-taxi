package pl.taxinav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalLinksTest {
    private fun point(s: String) = ExternalLinks.parse(s) as ExternalTarget.Point
    private fun query(s: String) = (ExternalLinks.parse(s) as ExternalTarget.Query).text

    @Test fun geo() {
        assertEquals(LatLon(53.1235, 18.0084), point("geo:53.1235,18.0084").pos)
        assertEquals(LatLon(53.1, 18.0), point("geo:53.1,18.0?z=17").pos)
        val p = point("geo:0,0?q=53.13,18.02(Dworzec Główny)")
        assertEquals(LatLon(53.13, 18.02), p.pos)
        assertEquals("Dworzec Główny", p.label)
        assertEquals("Gdańska 12, Bydgoszcz", query("geo:0,0?q=Gda%C5%84ska+12%2C+Bydgoszcz"))
        assertNull(ExternalLinks.parse("geo:0,0"))
    }

    @Test fun googleNavigation() {
        assertEquals(LatLon(53.1, 18.0), point("google.navigation:q=53.1,18.0").pos)
        assertEquals("Lotnisko Bydgoszcz", query("google.navigation:q=Lotnisko+Bydgoszcz&mode=d"))
    }

    @Test fun googleMapsUrls() {
        assertEquals(LatLon(53.1, 18.0), point("https://www.google.com/maps/dir/?api=1&destination=53.1,18.0&travelmode=driving").pos)
        assertEquals("Szeroka 1 Toruń", query("https://www.google.com/maps/search/?api=1&query=Szeroka%201%20Toru%C5%84"))
        assertEquals("Fordońska 120", query("https://maps.google.com/?daddr=Fordo%C5%84ska+120"))
        assertEquals(LatLon(53.0102, 18.6046), point("https://maps.google.com/maps?q=53.0102,18.6046").pos)
        val p = point("https://www.google.com/maps/place/Galeria+Pomorska/@53.1350,18.0300,17z/data=!3d53.1351!4d18.0301")
        assertEquals(LatLon(53.1351, 18.0301), p.pos)
        assertEquals("Galeria Pomorska", p.label)
        assertEquals(LatLon(53.2, 18.1), point("https://www.google.com/maps/@53.2,18.1,15z").pos)
        assertNull(ExternalLinks.parse("https://maps.app.goo.gl/AbCdEf123"))
    }

    @Test fun plainText() {
        assertEquals("Dworcowa 5", query("Dworcowa 5"))
        assertEquals(LatLon(52.5, 19.2), point("52.5, 19.2").pos)
    }
}
