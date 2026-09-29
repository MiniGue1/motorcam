package cz.motorcam.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Silniční síť z OpenStreetMap (odpověď Overpass API) v lokálních souřadnicích [m].
 * Uzly jsou body silnic, cesty (ways) jsou lomené čáry přes uzly.
 */
public final class RoadNetwork {

    /** Jedna silnice z OSM. */
    public static final class Way {
        public final int[] nodes;       // indexy uzlů
        public final String highway;    // typ silnice (primary, residential, track, ...)
        public final String name;
        public final String surface;    // OSM tag surface (asphalt, gravel, ...), může být null
        public final int oneway;        // 0 = obousměrná, 1 = jen ve směru uzlů, -1 = jen proti

        Way(int[] nodes, String highway, String name, String surface, int oneway) {
            this.nodes = nodes;
            this.highway = highway;
            this.name = name;
            this.surface = surface;
            this.oneway = oneway;
        }

        /** Nezpevněný povrch podle OSM (štěrk, hlína, ...). */
        public boolean unpaved() {
            if (surface == null) return "track".equals(highway);
            switch (surface) {
                case "unpaved": case "gravel": case "fine_gravel": case "compacted": case "dirt":
                case "earth": case "ground": case "mud": case "sand": case "grass": case "pebblestone":
                    return true;
                default:
                    return false;
            }
        }
    }

    public final Geo geo;
    public final double[] nx, ny;               // souřadnice uzlů [m]
    public final List<Way> ways;
    /** Pro každý uzel seznam dvojic {index cesty, pozice uzlu v cestě}. */
    public final List<List<int[]>> nodeWays;

    RoadNetwork(Geo geo, double[] nx, double[] ny, List<Way> ways) {
        this.geo = geo;
        this.nx = nx;
        this.ny = ny;
        this.ways = ways;
        nodeWays = new ArrayList<>(nx.length);
        for (int i = 0; i < nx.length; i++) nodeWays.add(new ArrayList<int[]>(2));
        for (int w = 0; w < ways.size(); w++) {
            int[] ns = ways.get(w).nodes;
            for (int k = 0; k < ns.length; k++) nodeWays.get(ns[k]).add(new int[]{w, k});
        }
    }

    /** Dotaz pro Overpass API: silnice sjízdné motorkou v okruhu radiusM kolem bodu. */
    public static String overpassQuery(double lat, double lon, int radiusM) {
        return String.format(Locale.US,
                "[out:json][timeout:25];way(around:%d,%.6f,%.6f)[highway~\"^(motorway|trunk|primary|secondary|"
                        + "tertiary|unclassified|residential|living_street|service|track|motorway_link|trunk_link|"
                        + "primary_link|secondary_link|tertiary_link)$\"];(._;>;);out body;",
                radiusM, lat, lon);
    }

    /** Zpracuje JSON odpověď Overpass API. Počátek lokálních souřadnic = (lat0, lon0). */
    public static RoadNetwork parseOverpass(String json, double lat0, double lon0) throws JSONException {
        Geo geo = new Geo(lat0, lon0);
        JSONArray el = new JSONObject(json).getJSONArray("elements");
        HashMap<Long, Integer> index = new HashMap<>();
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < el.length(); i++) {
            JSONObject o = el.getJSONObject(i);
            if ("node".equals(o.optString("type"))) {
                index.put(o.getLong("id"), pts.size());
                pts.add(new double[]{geo.x(o.getDouble("lon")), geo.y(o.getDouble("lat"))});
            }
        }
        List<Way> ways = new ArrayList<>();
        for (int i = 0; i < el.length(); i++) {
            JSONObject o = el.getJSONObject(i);
            if (!"way".equals(o.optString("type"))) continue;
            JSONArray ids = o.getJSONArray("nodes");
            int[] ns = new int[ids.length()];
            int n = 0;
            for (int k = 0; k < ids.length(); k++) {
                Integer idx = index.get(ids.getLong(k));
                if (idx != null) ns[n++] = idx;
            }
            if (n < 2) continue;
            int[] nodes = new int[n];
            System.arraycopy(ns, 0, nodes, 0, n);
            JSONObject tags = o.optJSONObject("tags");
            String highway = tags != null ? tags.optString("highway", "") : "";
            String name = tags != null ? tags.optString("name", tags.optString("ref", "")) : "";
            String surface = tags != null && tags.has("surface") ? tags.optString("surface") : null;
            String ow = tags != null ? tags.optString("oneway", "") : "";
            boolean impliedOneway = highway.equals("motorway") || (tags != null && "roundabout".equals(tags.optString("junction")));
            int oneway = ow.equals("yes") || ow.equals("1") || ow.equals("true") || (impliedOneway && !ow.equals("no")) ? 1
                    : ow.equals("-1") ? -1 : 0;
            ways.add(new Way(nodes, highway, name, surface, oneway));
        }
        double[] nx = new double[pts.size()], ny = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            nx[i] = pts.get(i)[0];
            ny[i] = pts.get(i)[1];
        }
        return new RoadNetwork(geo, nx, ny, ways);
    }
}
