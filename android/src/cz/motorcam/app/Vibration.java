package cz.motorcam.app;

import java.util.ArrayDeque;

/**
 * Odhad nerovnosti povrchu z vibrací (akcelerometr mobilu na řídítkách).
 *
 * 1. Gravitace se odečte dolní propustí (zbytek = otřesy).
 * 2. Z velikosti otřesů se počítá RMS za posledních windowS sekund.
 * 3. RMS se porovná se „základní“ hodnotou na hladkém asfaltu (kalibrace):
 *      poměr < 1,8 → hladký, < 3 → nerovný, jinak velmi nerovný.
 * Při stání (< 8 km/h) se neodhaduje – vibrace jsou jen od motoru.
 *
 * Slouží jako potvrzení kamery a jako náhrada, dokud není natrénovaný model povrchu.
 */
public final class Vibration {
    private final double windowS;
    private final double[] gravity = new double[3];
    private boolean init;
    private final ArrayDeque<double[]> samples = new ArrayDeque<>();   // {čas s, kvadrát otřesu}
    private double sumSq;

    public double rms;          // aktuální RMS otřesů [m/s²]

    public Vibration(double windowS) {
        this.windowS = windowS;
    }

    /** Přidá vzorek akcelerometru (čas v sekundách, zrychlení v m/s²). */
    public void add(double t, double ax, double ay, double az) {
        double[] a = {ax, ay, az};
        if (!init) {
            System.arraycopy(a, 0, gravity, 0, 3);
            init = true;
        }
        double sq = 0;
        for (int i = 0; i < 3; i++) {
            gravity[i] = 0.95 * gravity[i] + 0.05 * a[i];
            double lin = a[i] - gravity[i];
            sq += lin * lin;
        }
        samples.addLast(new double[]{t, sq});
        sumSq += sq;
        while (!samples.isEmpty() && samples.peekFirst()[0] < t - windowS) sumSq -= samples.removeFirst()[1];
        rms = samples.isEmpty() ? 0 : Math.sqrt(Math.max(0, sumSq) / samples.size());
    }

    /** Stupeň nerovnosti 0/1/2, nebo -1 když se nedá určit. */
    public int level(double speedKmh, double baseline) {
        if (samples.size() < 10 || Double.isNaN(speedKmh) || speedKmh < 8) return -1;
        double ratio = rms / Math.max(0.05, baseline);
        return ratio < 1.8 ? 0 : ratio < 3.0 ? 1 : 2;
    }
}
