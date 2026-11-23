package app.mockgps;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls coordinates and place names out of Google Maps links, geo: URIs and shared text.
 * Plain Java (no Android APIs) so it can be unit-tested off-device.
 */
public final class MapsLinkParser {

    /** A location found in a link or text. */
    public static final class Place {
        public final double lat;
        public final double lng;
        /** Place name, or null if the link doesn't carry one. */
        public final String name;
        /** True for an exact place pin; false when it's only the map's centre. */
        public final boolean precise;

        Place(double lat, double lng, String name, boolean precise) {
            this.lat = lat;
            this.lng = lng;
            this.name = name;
            this.precise = precise;
        }

        public Place withName(String newName) {
            return new Place(lat, lng, newName, precise);
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s(%.6f, %.6f, %s)", precise ? "pin" : "centre", lat, lng, name);
        }
    }

    private static final String NUM = "(-?\\d{1,3}(?:\\.\\d+)?)";
    private static final String DEC = "(-?\\d{1,3}\\.\\d+)";

    /** The selected place's pin inside Google's data= blob: ...!3d<lat>!4d<lng>... */
    private static final Pattern PIN = Pattern.compile("!3d" + NUM + "!4d" + NUM);
    /** Map centre in the path: /@<lat>,<lng>,<zoom>z */
    private static final Pattern CENTRE = Pattern.compile("@" + NUM + "," + NUM);
    private static final Pattern WHOLE_PAIR = Pattern.compile("^\\s*" + NUM + "\\s*[,;\\s]\\s*" + NUM + "\\s*$");
    private static final Pattern PAIR_IN_TEXT = Pattern.compile(DEC + "\\s*,\\s*" + DEC);
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACE_NAME = Pattern.compile("/maps/place/([^/@?#]+)");
    private static final Pattern GOOGLE_MAPS = Pattern.compile(
            "^https?://(maps\\.app\\.goo\\.gl|goo\\.gl/maps|g\\.co/kgs|maps\\.google\\.[a-z.]+"
                    + "|(www\\.)?google\\.[a-z.]+/maps)(/|\\?|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GEO_LABEL = Pattern.compile("^" + NUM + "\\s*,\\s*" + NUM + "\\s*(?:\\((.*)\\))?\\s*$");
    private static final String[] POINT_PARAMS = {"q", "query", "ll", "destination", "daddr", "center"};

    private MapsLinkParser() {
    }

    /** Coordinates in a Google Maps (or similar) URL, preferring an exact place pin. */
    public static Place parseUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        String name = placeName(url);
        String decoded = decode(url);

        Matcher pin = lastMatch(PIN, decoded);
        if (pin != null) {
            Place p = place(pin.group(1), pin.group(2), name, true);
            if (p != null) return p;
        }
        for (String key : POINT_PARAMS) {
            String v = queryParam(url, key);
            if (v == null) continue;
            Place p = parseCoordinates(v);
            if (p != null) return new Place(p.lat, p.lng, name, true);
        }
        Matcher centre = CENTRE.matcher(decoded);
        if (centre.find()) {
            return place(centre.group(1), centre.group(2), name, false);
        }
        return null;
    }

    /** "1.2834, 103.8607", "1.2834 103.8607", or decimal coordinates anywhere in the text. */
    public static Place parseCoordinates(String text) {
        if (text == null) return null;
        Matcher m = WHOLE_PAIR.matcher(text);
        if (!m.find()) {
            m = PAIR_IN_TEXT.matcher(text);
            if (!m.find()) return null;
        }
        return place(m.group(1), m.group(2), null, true);
    }

    /**
     * geo:lat,lng / geo:0,0?q=lat,lng(Label). Returns null for geo:0,0?q=some+address;
     * use {@link #geoQuery} to get that text.
     */
    public static Place parseGeo(String uri) {
        if (uri == null || !uri.regionMatches(true, 0, "geo:", 0, 4)) return null;
        String q = geoQuery(uri);
        if (q != null) {
            Matcher m = GEO_LABEL.matcher(q);
            if (m.find()) {
                String label = m.group(3);
                return place(m.group(1), m.group(2), label == null || label.trim().isEmpty() ? null : label.trim(), true);
            }
        }
        String path = uri.substring(4);
        if (path.startsWith("//")) path = path.substring(2);
        int cut = indexOfAny(path, '?', ';', '#');
        if (cut >= 0) path = path.substring(0, cut);
        String[] parts = path.split(",");  // lat,lng[,altitude]
        if (parts.length >= 2) {
            Place p = place(parts[0].trim(), parts[1].trim(), null, true);
            if (p != null && (p.lat != 0 || p.lng != 0)) return p;
        }
        return null;
    }

    /** The q= text of a geo: URI, decoded, or null. */
    public static String geoQuery(String uri) {
        String q = queryParam(uri, "q");
        return q == null || q.trim().isEmpty() ? null : q.trim();
    }

    /** First http(s) URL in a block of text, without trailing punctuation. */
    public static String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = URL.matcher(text);
        if (!m.find()) return null;
        String url = m.group();
        while (!url.isEmpty() && ".,;:!?)]}".indexOf(url.charAt(url.length() - 1)) >= 0) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public static boolean isGoogleMapsUrl(String url) {
        return url != null && GOOGLE_MAPS.matcher(url).find();
    }

    /**
     * Google Maps search for {@code query} (or a plain map if blank) around the given view.
     * Below zoom 5 the view is too wide to be useful, so Google picks the area itself.
     */
    public static String googleMapsUrl(String query, double lat, double lng, double zoom) {
        String at = zoom < 5 ? ""
                : String.format(Locale.US, "/@%.6f,%.6f,%.1fz", lat, lng, Math.min(20, zoom));
        if (query == null || query.trim().isEmpty()) return "https://www.google.com/maps" + at;
        return "https://www.google.com/maps/search/" + encode(query.trim()) + at;
    }

    /** First non-empty line of shared text that isn't a URL, e.g. the place name Google Maps puts first. */
    public static String firstTextLine(String text) {
        if (text == null) return null;
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !URL.matcher(t).lookingAt()) return t;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------

    private static String placeName(String url) {
        Matcher m = PLACE_NAME.matcher(url);
        if (!m.find()) return null;
        String n = decode(m.group(1)).trim();
        return n.isEmpty() ? null : n;
    }

    private static String queryParam(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        int end = url.indexOf('#', q);
        String query = end < 0 ? url.substring(q + 1) : url.substring(q + 1, end);
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equalsIgnoreCase(key)) {
                return decode(part.substring(eq + 1));
            }
        }
        return null;
    }

    private static Matcher lastMatch(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int start = -1;
        while (m.find()) start = m.start();
        if (start < 0) return null;
        m = p.matcher(s);
        m.find(start);
        return m;
    }

    private static Place place(String latS, String lngS, String name, boolean precise) {
        try {
            double lat = Double.parseDouble(latS);
            double lng = Double.parseDouble(lngS);
            if (Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
            return new Place(lat, lng, name, precise);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int indexOfAny(String s, char... chars) {
        int best = -1;
        for (char c : chars) {
            int i = s.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
