package cz.motorcam.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Motorkářská trasa: průjezdní body (obce, sedla) + spočítaná geometrie po silnicích.
 * Předpřipravené trasy jsou v assets/trasy.json, vlastní trasy v souborech aplikace.
 */
public final class Route {

    /** Průjezdní bod. */
    public static final class Waypoint {
        public final String name;
        public final double lat, lon;

        public Waypoint(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    public String id, name, region = "", type = "tam", difficulty = "", description = "";
    public boolean builtin;
    public boolean avoidMotorway = true, curvy;
    public final List<Waypoint> waypoints = new ArrayList<>();
    // spočítaná trasa (null = zatím nespočítaná)
    public double[] lat, lon;
    public double distanceM, durationS;

    public boolean hasGeometry() {
        return lat != null && lat.length > 1;
    }

    // ------------------------------------------------------------------ JSON

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id).put("nazev", name).put("oblast", region).put("typ", type)
                .put("obtiznost", difficulty).put("popis", description)
                .put("bez_dalnic", avoidMotorway).put("zatackovy", curvy);
        JSONArray wps = new JSONArray();
        for (Waypoint w : waypoints) {
            wps.put(new JSONObject().put("nazev", w.name).put("lat", w.lat).put("lon", w.lon));
        }
        o.put("body", wps);
        if (hasGeometry()) {
            JSONArray g = new JSONArray();
            for (int i = 0; i < lat.length; i++) g.put(Math.round(lat[i] * 1e6) / 1e6).put(Math.round(lon[i] * 1e6) / 1e6);
            o.put("geometrie", g).put("delka_m", distanceM).put("cas_s", durationS);
        }
        return o;
    }

    /** Trasa uložená aplikací (body jako objekty). */
    public static Route fromJson(JSONObject o) throws JSONException {
        Route r = new Route();
        r.id = o.getString("id");
        r.name = o.optString("nazev", r.id);
        r.region = o.optString("oblast", "");
        r.type = o.optString("typ", "tam");
        r.difficulty = o.optString("obtiznost", "");
        r.description = o.optString("popis", "");
        r.avoidMotorway = o.optBoolean("bez_dalnic", true);
        r.curvy = o.optBoolean("zatackovy", false);
        JSONArray wps = o.getJSONArray("body");
        for (int i = 0; i < wps.length(); i++) {
            JSONObject w = wps.getJSONObject(i);
            r.waypoints.add(new Waypoint(w.optString("nazev", "bod " + (i + 1)), w.getDouble("lat"), w.getDouble("lon")));
        }
        JSONArray g = o.optJSONArray("geometrie");
        if (g != null && g.length() >= 4) {
            int n = g.length() / 2;
            r.lat = new double[n];
            r.lon = new double[n];
            for (int i = 0; i < n; i++) {
                r.lat[i] = g.getDouble(2 * i);
                r.lon[i] = g.getDouble(2 * i + 1);
            }
            r.distanceM = o.optDouble("delka_m", 0);
            r.durationS = o.optDouble("cas_s", 0);
        }
        return r;
    }

    /** Knihovna předpřipravených tras: místa (id -> bod) + trasy odkazující na id míst. */
    public static final class Library {
        public final Map<String, Waypoint> places = new LinkedHashMap<>();
        public final List<Route> routes = new ArrayList<>();
    }

    public static Library parseLibrary(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        Library lib = new Library();
        JSONObject places = root.getJSONObject("mista");
        JSONArray names = places.names();
        for (int i = 0; names != null && i < names.length(); i++) {
            String key = names.getString(i);
            JSONObject p = places.getJSONObject(key);
            lib.places.put(key, new Waypoint(p.getString("nazev"), p.getDouble("lat"), p.getDouble("lon")));
        }
        JSONArray routes = root.getJSONArray("trasy");
        for (int i = 0; i < routes.length(); i++) {
            JSONObject o = routes.getJSONObject(i);
            Route r = new Route();
            r.builtin = true;
            r.id = o.getString("id");
            r.name = o.getString("nazev");
            r.region = o.optString("oblast", "");
            r.type = o.optString("typ", "tam");
            r.difficulty = o.optString("obtiznost", "");
            r.description = o.optString("popis", "");
            JSONArray body = o.getJSONArray("body");
            for (int k = 0; k < body.length(); k++) {
                Waypoint w = lib.places.get(body.getString(k));
                if (w == null) throw new JSONException("Neznámé místo " + body.getString(k) + " v trase " + r.id);
                r.waypoints.add(w);
            }
            lib.routes.add(r);
        }
        return lib;
    }

    /** Délka lomené čáry geometrie [m]. */
    public static double length(double[] lat, double[] lon) {
        double d = 0;
        for (int i = 1; i < lat.length; i++) d += Geo.distance(lat[i - 1], lon[i - 1], lat[i], lon[i]);
        return d;
    }
}
