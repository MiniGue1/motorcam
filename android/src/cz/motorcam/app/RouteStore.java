package cz.motorcam.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Ukládání tras:
 *   assets/trasy.json          – předpřipravené trasy (jen body)
 *   files/trasy_cache/<id>.json – spočítaná geometrie předpřipravených tras
 *   files/trasy/<id>.json       – vlastní trasy uživatele
 * Aktivní trasa (po které se jede) je v SharedPreferences.
 */
public final class RouteStore {
    private static final String PREFS = "motorcam", KEY_ACTIVE = "aktivniTrasa";
    private final Context ctx;
    private Route.Library library;
    public String loadError;           // proč se nepodařilo načíst předpřipravené trasy

    public RouteStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public Route.Library library() {
        if (library == null) {
            try {
                String json = new String(MapService.readAll(ctx.getAssets().open("trasy.json")), StandardCharsets.UTF_8);
                library = Route.parseLibrary(json);
            } catch (Exception e) {
                library = new Route.Library();
                loadError = e.toString();
            }
            for (Route r : library.routes) {
                Route cached = read(new File(dir("trasy_cache"), r.id + ".json"));
                if (cached != null && cached.hasGeometry()) {
                    r.lat = cached.lat;
                    r.lon = cached.lon;
                    r.distanceM = cached.distanceM;
                    r.durationS = cached.durationS;
                }
            }
        }
        return library;
    }

    public List<Route> userRoutes() {
        List<Route> out = new ArrayList<>();
        File[] files = dir("trasy").listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            Route r = read(f);
            if (r != null) out.add(r);
        }
        return out;
    }

    public Route find(String id) {
        if (id == null) return null;
        for (Route r : library().routes) if (r.id.equals(id)) return r;
        for (Route r : userRoutes()) if (r.id.equals(id)) return r;
        return null;
    }

    public void save(Route r) throws Exception {
        if (r.id == null) r.id = String.format(Locale.US, "vlastni_%d", System.currentTimeMillis());
        File f = new File(dir(r.builtin ? "trasy_cache" : "trasy"), r.id + ".json");
        MapService.writeAll(f, r.toJson().toString().getBytes(StandardCharsets.UTF_8));
    }

    public void delete(Route r) {
        new File(dir("trasy"), r.id + ".json").delete();
        if (r.id.equals(activeId())) setActive(null);
    }

    public String activeId() {
        return prefs().getString(KEY_ACTIVE, null);
    }

    public void setActive(String id) {
        SharedPreferences.Editor e = prefs().edit();
        if (id == null) e.remove(KEY_ACTIVE);
        else e.putString(KEY_ACTIVE, id);
        e.apply();
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private File dir(String name) {
        File d = new File(ctx.getFilesDir(), name);
        d.mkdirs();
        return d;
    }

    private static Route read(File f) {
        try {
            return Route.fromJson(new JSONObject(new String(MapService.readAll(new java.io.FileInputStream(f)),
                    StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}
