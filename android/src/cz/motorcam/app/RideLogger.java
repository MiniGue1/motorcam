package cz.motorcam.app;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Záznam senzorů během jízdy do CSV (stejný čas jako video => snadné spárování).
 *
 * Sloupce: cas_s, typ, lat, lon, rychlost_kmh, kurz_deg, presnost_m, ax, ay, az, gx, gy, gz,
 *          kontrolka, povrch, duvod
 * typ: gps | acc | gyr | stav (stav aplikace 10× za sekundu) | udalost (video_start)
 * Formát čte Python (src/fusion/sensors.py) pro vyhodnocení a demo video.
 */
public final class RideLogger {
    public static final String HEADER =
            "cas_s,typ,lat,lon,rychlost_kmh,kurz_deg,presnost_m,ax,ay,az,gx,gy,gz,kontrolka,povrch,duvod\n";
    private final BufferedWriter out;
    private final long startNs;
    public final File file;

    public RideLogger(File file, long startNs) throws IOException {
        this.file = file;
        this.startNs = startNs;
        out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8), 1 << 16);
        out.write(HEADER);
    }

    private double t(long ns) {
        return (ns - startNs) / 1e9;
    }

    public synchronized void gps(long ns, double lat, double lon, double kmh, double bearing, double acc) {
        write(String.format(Locale.US, "%.3f,gps,%.7f,%.7f,%.2f,%.1f,%.1f,,,,,,,,,\n", t(ns), lat, lon, kmh, bearing, acc));
    }

    public synchronized void acc(long ns, float x, float y, float z) {
        write(String.format(Locale.US, "%.3f,acc,,,,,,%.3f,%.3f,%.3f,,,,,,\n", t(ns), x, y, z));
    }

    public synchronized void gyr(long ns, float x, float y, float z) {
        write(String.format(Locale.US, "%.3f,gyr,,,,,,,,,%.4f,%.4f,%.4f,,,\n", t(ns), x, y, z));
    }

    public synchronized void state(long ns, int lamp, String surface, String reason) {
        write(String.format(Locale.US, "%.3f,stav,,,,,,,,,,,,%d,%s,\"%s\"\n", t(ns), lamp, surface, reason.replace("\"", "'")));
    }

    /** Událost (např. video_start = okamžik, kdy začal první snímek videa). */
    public synchronized void event(long ns, String name) {
        write(String.format(Locale.US, "%.3f,udalost,,,,,,,,,,,,,,\"%s\"\n", t(ns), name));
    }

    private void write(String s) {
        try {
            out.write(s);
        } catch (IOException ignored) {
        }
    }

    public synchronized void close() {
        try {
            out.close();
        } catch (IOException ignored) {
        }
    }
}
