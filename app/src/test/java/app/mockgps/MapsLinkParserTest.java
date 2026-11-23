package app.mockgps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MapsLinkParserTest {

    private static final double EPS = 1e-7;

    @Test
    public void placeUrlUsesPinNotMapCentre() {
        MapsLinkParser.Place p = MapsLinkParser.parseUrl(
                "https://www.google.com/maps/place/Marina+Bay+Sands+Singapore/@1.2838785,103.8565965,17z/"
                        + "data=!3m1!4b1!4m9!3m8!1s0x31da19045b5c3c7b:0x3c1b6b0a3a5dbcd0!5m2!4m1!1i2!8m2"
                        + "!3d1.2838785!4d103.8591714!16zL20vMDdmXzFi?entry=ttu");
        assertNotNull(p);
        assertTrue(p.precise);
        assertEquals(1.2838785, p.lat, EPS);
        assertEquals(103.8591714, p.lng, EPS);
        assertEquals("Marina Bay Sands Singapore", p.name);
    }

    @Test
    public void percentEncodedNameAndNegativeCoordinates() {
        MapsLinkParser.Place p = MapsLinkParser.parseUrl(
                "https://www.google.com/maps/place/Sydney+Opera+House/@-33.8567844,151.2127218,17z/"
                        + "data=!4m6!3m5!1s0x6b12ae665e892fdd:0x3133f8d75a1ac251!8m2!3d-33.8567844!4d151.2152967");
        assertNotNull(p);
        assertEquals(-33.8567844, p.lat, EPS);
        assertEquals(151.2152967, p.lng, EPS);

        MapsLinkParser.Place q = MapsLinkParser.parseUrl(
                "https://www.google.com/maps/place/Caf%C3%A9+de+Flore/@48.854,2.333,17z/data=!3d48.8541!4d2.3326");
        assertEquals("Café de Flore", q.name);
    }

    @Test
    public void droppedPin() {
        MapsLinkParser.Place p = MapsLinkParser.parseUrl(
                "https://www.google.com/maps/place/1%C2%B017'00.2%22N+103%C2%B051'38.5%22E/@1.2833833,103.8584,17z/"
                        + "data=!3m1!4b1!4m4!3m3!8m2!3d1.2833833!4d103.8607");
        assertTrue(p.precise);
        assertEquals(103.8607, p.lng, EPS);
    }

    @Test
    public void mapViewOnlyGivesCentre() {
        MapsLinkParser.Place p = MapsLinkParser.parseUrl("https://www.google.com/maps/@1.2903,103.8519,15z?entry=ttu");
        assertNotNull(p);
        assertFalse(p.precise);
        assertEquals(1.2903, p.lat, EPS);
        assertNull(p.name);

        MapsLinkParser.Place s = MapsLinkParser.parseUrl(
                "https://www.google.com/maps/search/coffee/@1.30,103.83,14z?entry=ttu");
        assertFalse(s.precise);
    }

    @Test
    public void queryParameters() {
        MapsLinkParser.Place a = MapsLinkParser.parseUrl("https://maps.google.com/?q=1.2834,103.8607");
        assertTrue(a.precise);
        assertEquals(1.2834, a.lat, EPS);

        MapsLinkParser.Place b = MapsLinkParser.parseUrl(
                "https://www.google.com/maps/search/?api=1&query=1.2834%2C103.8607");
        assertEquals(103.8607, b.lng, EPS);

        MapsLinkParser.Place c = MapsLinkParser.parseUrl("https://maps.google.com/maps?ll=35.681236,139.767125&z=16");
        assertEquals(35.681236, c.lat, EPS);
    }

    @Test
    public void placeWithoutCoordinates() {
        assertNull(MapsLinkParser.parseUrl(
                "https://www.google.com/maps/place/Marina+Bay+Sands/data=!4m2!3m1!1s0x31da19045b5c3c7b:0x3c1b6b0a3a5dbcd0"));
        assertNull(MapsLinkParser.parseUrl("https://maps.app.goo.gl/AbCdEf123"));
    }

    @Test
    public void geoUris() {
        MapsLinkParser.Place a = MapsLinkParser.parseGeo("geo:1.2834,103.8607?z=17");
        assertEquals(1.2834, a.lat, EPS);
        MapsLinkParser.Place b = MapsLinkParser.parseGeo("geo:0,0?q=1.2834,103.8607(Marina%20Bay)");
        assertEquals("Marina Bay", b.name);
        assertEquals(103.8607, b.lng, EPS);
        assertNull(MapsLinkParser.parseGeo("geo:0,0?q=Marina+Bay+Sands"));
        assertEquals("Marina Bay Sands", MapsLinkParser.geoQuery("geo:0,0?q=Marina+Bay+Sands"));
        assertEquals(10.5, MapsLinkParser.parseGeo("geo:10.5,20.25,30").lat, EPS);
    }

    @Test
    public void sharedText() {
        String shared = "Marina Bay Sands\n10 Bayfront Ave, Singapore 018956\nhttps://maps.app.goo.gl/AbCdEf123";
        String url = MapsLinkParser.extractUrl(shared);
        assertEquals("https://maps.app.goo.gl/AbCdEf123", url);
        assertTrue(MapsLinkParser.isGoogleMapsUrl(url));
        assertEquals("Marina Bay Sands", MapsLinkParser.firstTextLine(shared));
        assertEquals("https://goo.gl/maps/xyz", MapsLinkParser.extractUrl("See (https://goo.gl/maps/xyz)."));
    }

    @Test
    public void recognisesGoogleMapsHosts() {
        assertTrue(MapsLinkParser.isGoogleMapsUrl("https://www.google.com/maps/place/X"));
        assertTrue(MapsLinkParser.isGoogleMapsUrl("https://www.google.com.sg/maps?q=1,2"));
        assertTrue(MapsLinkParser.isGoogleMapsUrl("https://maps.google.com/?q=1,2"));
        assertTrue(MapsLinkParser.isGoogleMapsUrl("https://goo.gl/maps/abc"));
        assertFalse(MapsLinkParser.isGoogleMapsUrl("https://www.google.com/search?q=maps"));
        assertFalse(MapsLinkParser.isGoogleMapsUrl("https://example.com/maps"));
    }

    @Test
    public void coordinatesInText() {
        assertEquals(1.2834, MapsLinkParser.parseCoordinates("1.2834, 103.8607").lat, EPS);
        assertEquals(103.8607, MapsLinkParser.parseCoordinates("1.2834 103.8607").lng, EPS);
        assertEquals(-33.85, MapsLinkParser.parseCoordinates("Meet at -33.85, 151.21 ok").lat, EPS);
        assertNull(MapsLinkParser.parseCoordinates("Route 66, 12 miles"));
        assertNull(MapsLinkParser.parseCoordinates("95.0, 10.0"));
    }

    @Test
    public void buildsSearchUrls() {
        assertEquals("https://www.google.com/maps/search/coffee+near+orchard/@1.300000,103.830000,15.0z",
                MapsLinkParser.googleMapsUrl("coffee near orchard", 1.3, 103.83, 15));
        assertEquals("https://www.google.com/maps", MapsLinkParser.googleMapsUrl("  ", 20, 0, 2));
        assertEquals("https://www.google.com/maps/search/Caf%C3%A9+%26+bar",
                MapsLinkParser.googleMapsUrl("Café & bar", 20, 0, 3));
        assertEquals("https://www.google.com/maps/@1.300000,103.830000,12.0z",
                MapsLinkParser.googleMapsUrl(null, 1.3, 103.83, 12));
    }
}
