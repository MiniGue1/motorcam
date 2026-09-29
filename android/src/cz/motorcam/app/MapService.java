package cz.motorcam.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Stahování silnic z OpenStreetMap přes Overpass API (zdarma, bez klíče).
 * Poslední odpověď se ukládá do mezipaměti, aby šla použít i bez signálu.
 */
public final class MapService {
    public static final int RADIUS_M = 2000;
    private static final String[] ENDPOINTS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    };

    public interface Callback {
        void onLoaded(RoadNetwork net, double lat, double lon, boolean fromCache);

        void onError(String message);
    }

    private final File cacheDir;
    private volatile boolean busy;

    public MapService(File cacheDir) {
        this.cacheDir = cacheDir;
    }

    public boolean isBusy() {
        return busy;
    }

    /** Načte mapu z mezipaměti, pokud je střed blízko (do 1 km). Volat mimo UI vlákno. */
    public RoadNetwork loadCache(double lat, double lon, double[] centerOut) {
        try {
            File meta = new File(cacheDir, "osm_cache.txt"), data = new File(cacheDir, "osm_cache.json");
            if (!meta.exists() || !data.exists()) return null;
            String[] parts = new String(readAll(new FileInputStream(meta)), StandardCharsets.UTF_8).trim().split(",");
            double clat = Double.parseDouble(parts[0]), clon = Double.parseDouble(parts[1]);
            if (Geo.distance(lat, lon, clat, clon) > 1000) return null;
            centerOut[0] = clat;
            centerOut[1] = clon;
            return RoadNetwork.parseOverpass(new String(readAll(new FileInputStream(data)), StandardCharsets.UTF_8), clat, clon);
        } catch (Exception e) {
            return null;
        }
    }

    /** Asynchronně stáhne silnice kolem bodu. */
    public void fetch(final double lat, final double lon, final Callback cb) {
        if (busy) return;
        busy = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    double[] c = new double[2];
                    RoadNetwork cached = loadCache(lat, lon, c);
                    if (cached != null) {
                        cb.onLoaded(cached, c[0], c[1], true);
                        return;
                    }
                    String query = RoadNetwork.overpassQuery(lat, lon, RADIUS_M);
                    String json = null;
                    Exception last = null;
                    for (String endpoint : ENDPOINTS) {
                        try {
                            json = post(endpoint, "data=" + URLEncoder.encode(query, "UTF-8"));
                            break;
                        } catch (Exception e) {
                            last = e;
                        }
                    }
                    if (json == null) throw last != null ? last : new Exception("bez odpovědi");
                    RoadNetwork net = RoadNetwork.parseOverpass(json, lat, lon);
                    try {
                        writeAll(new File(cacheDir, "osm_cache.json"), json.getBytes(StandardCharsets.UTF_8));
                        writeAll(new File(cacheDir, "osm_cache.txt"),
                                String.format(Locale.US, "%.7f,%.7f", lat, lon).getBytes(StandardCharsets.UTF_8));
                    } catch (Exception ignored) {
                    }
                    cb.onLoaded(net, lat, lon, false);
                } catch (Exception e) {
                    cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    busy = false;
                }
            }
        }, "mapa");
        t.setDaemon(true);
        t.start();
    }

    private static String post(String endpoint, String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(endpoint).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(40000);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("User-Agent", "MotorCam/0.1 (studentsky projekt)");
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        OutputStream os = con.getOutputStream();
        os.write(body.getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = con.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        return new String(readAll(con.getInputStream()), StandardCharsets.UTF_8);
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 15];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    static void writeAll(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
    }
}
