package cz.motorcam.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Výpočet trasy po silnicích přes veřejný router OSRM (data OpenStreetMap, zdarma).
 *
 * Zatáčkový režim: pro každý úsek mezi dvěma průjezdními body si vyžádá alternativní
 * varianty a vybere tu s nejvyšším skóre zatáčkovitosti (Curviness).
 *
 * Veřejné servery OSRM jsou určené pro lehké použití – aplikace volá router jen při
 * plánování (ne za jízdy) a mezi požadavky čeká 1 s.
 */
public final class RoutingService {
    static final String[] SERVERS = {
            "https://router.project-osrm.org/route/v1/driving/",
            "https://routing.openstreetmap.de/routed-car/route/v1/driving/",
    };

    /** Jedna varianta trasy. */
    public static final class Candidate {
        public double[] lat, lon;
        public double distanceM, durationS;
        public Curviness.Stats stats;
    }

    public interface Progress {
        void onProgress(String message);
    }

    /** URL požadavku OSRM (souřadnice v pořadí lon,lat). */
    static String buildUrl(String base, List<Route.Waypoint> pts, boolean alternatives, boolean avoidMotorway) {
        StringBuilder b = new StringBuilder(base);
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) b.append(';');
            b.append(String.format(Locale.US, "%.6f,%.6f", pts.get(i).lon, pts.get(i).lat));
        }
        b.append("?overview=full&geometries=geojson&steps=false");
        if (alternatives) b.append("&alternatives=3");
        if (avoidMotorway) b.append("&exclude=motorway");
        return b.toString();
    }

    /** Zpracování odpovědi OSRM -> varianty tras. */
    static List<Candidate> parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (!"Ok".equals(root.optString("code"))) {
            throw new JSONException("Router: " + root.optString("code") + " " + root.optString("message"));
        }
        JSONArray routes = root.getJSONArray("routes");
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < routes.length(); i++) {
            JSONObject r = routes.getJSONObject(i);
            JSONArray coords = r.getJSONObject("geometry").getJSONArray("coordinates");
            Candidate c = new Candidate();
            c.lat = new double[coords.length()];
            c.lon = new double[coords.length()];
            for (int k = 0; k < coords.length(); k++) {
                JSONArray p = coords.getJSONArray(k);
                c.lon[k] = p.getDouble(0);
                c.lat[k] = p.getDouble(1);
            }
            c.distanceM = r.optDouble("distance", Route.length(c.lat, c.lon));
            c.durationS = r.optDouble("duration", 0);
            out.add(c);
        }
        return out;
    }

    /** Vybere variantu s nejvyšším skóre zatáčkovitosti. Varianty o víc než 60 % delší než
     *  nejkratší se nepočítají (nechceme objížďku přes půl kraje). */
    static Candidate curviest(List<Candidate> cands) {
        double shortest = Double.MAX_VALUE;
        for (Candidate c : cands) shortest = Math.min(shortest, c.distanceM);
        Candidate best = null;
        for (Candidate c : cands) {
            if (c.stats == null) c.stats = Curviness.analyze(c.lat, c.lon);
            if (c.distanceM > 1.6 * shortest) continue;
            if (best == null || c.stats.score > best.stats.score) best = c;
        }
        return best;
    }

    /** Spojí po sobě jdoucí úseky do jedné geometrie (bez zdvojení společných bodů). */
    static Candidate concat(List<Candidate> legs) {
        int n = 0;
        for (int i = 0; i < legs.size(); i++) n += legs.get(i).lat.length - (i > 0 ? 1 : 0);
        Candidate c = new Candidate();
        c.lat = new double[n];
        c.lon = new double[n];
        int k = 0;
        for (int i = 0; i < legs.size(); i++) {
            Candidate l = legs.get(i);
            for (int j = i > 0 ? 1 : 0; j < l.lat.length; j++) {
                c.lat[k] = l.lat[j];
                c.lon[k++] = l.lon[j];
            }
            c.distanceM += l.distanceM;
            c.durationS += l.durationS;
        }
        return c;
    }

    /** Spočítá trasu (blokující – volat mimo UI vlákno). Výsledek zapíše do route. */
    public static void compute(Route route, Progress progress) throws Exception {
        List<Route.Waypoint> pts = new ArrayList<>(route.waypoints);
        if ("okruh".equals(route.type) && pts.size() >= 2) {
            Route.Waypoint first = pts.get(0), last = pts.get(pts.size() - 1);
            if (Geo.distance(first.lat, first.lon, last.lat, last.lon) > 50) pts.add(first);
        }
        if (pts.size() < 2) throw new Exception("Trasa potřebuje aspoň 2 body");
        Candidate result;
        if (route.curvy) {
            List<Candidate> legs = new ArrayList<>();
            for (int i = 0; i + 1 < pts.size(); i++) {
                progress.onProgress(String.format(Locale.US, "Hledám nejzatáčkovější variantu %d/%d…", i + 1, pts.size() - 1));
                List<Route.Waypoint> pair = new ArrayList<>();
                pair.add(pts.get(i));
                pair.add(pts.get(i + 1));
                legs.add(curviest(request(pair, true, route.avoidMotorway)));
                if (i + 2 < pts.size()) Thread.sleep(1000);    // šetrně k veřejnému serveru
            }
            result = concat(legs);
        } else {
            progress.onProgress("Počítám trasu…");
            result = request(pts, false, route.avoidMotorway).get(0);
        }
        route.lat = result.lat;
        route.lon = result.lon;
        route.distanceM = result.distanceM;
        route.durationS = result.durationS;
    }

    private static List<Candidate> request(List<Route.Waypoint> pts, boolean alternatives, boolean avoidMotorway)
            throws Exception {
        Exception last = null;
        for (String server : SERVERS) {
            // některé servery nemají zapnuté vyloučení dálnic -> zkusit i bez něj
            boolean[] modes = avoidMotorway ? new boolean[]{true, false} : new boolean[]{false};
            for (boolean avoid : modes) {
                try {
                    return parse(get(buildUrl(server, pts, alternatives, avoid)));
                } catch (Exception e) {
                    last = e;
                }
            }
        }
        throw last != null ? last : new Exception("Router nedostupný");
    }

    static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(40000);
        con.setRequestProperty("User-Agent", "MotorCam/0.2 (studentsky projekt)");
        int code = con.getResponseCode();
        InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream();
        String body = in != null ? new String(MapService.readAll(in), StandardCharsets.UTF_8) : "";
        if (code >= 400 && !body.contains("\"code\"")) throw new Exception("HTTP " + code);
        return body;
    }

    /** Vyhledání místa podle názvu (Nominatim / OpenStreetMap). */
    public static List<Route.Waypoint> search(String query) throws Exception {
        String url = "https://nominatim.openstreetmap.org/search?format=json&limit=6&accept-language=cs"
                + "&countrycodes=cz,pl,sk,at,de&q=" + java.net.URLEncoder.encode(query, "UTF-8");
        JSONArray arr = new JSONArray(get(url));
        List<Route.Waypoint> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String name = o.optString("display_name", query);
            // zkrátit „Jeseník, okres Jeseník, Olomoucký kraj, Česko“ na první dvě části
            String[] parts = name.split(", ");
            String shortName = parts.length > 1 ? parts[0] + ", " + parts[1] : parts[0];
            out.add(new Route.Waypoint(shortName, o.getDouble("lat"), o.getDouble("lon")));
        }
        return out;
    }
}
