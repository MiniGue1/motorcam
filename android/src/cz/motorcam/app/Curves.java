package cz.motorcam.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Zatáčky na trase: poloměr zakřivení, doporučená rychlost a varování.
 *
 * Poloměr v bodě i = poloměr kružnice opsané třem bodům (i-k, i, i+k):
 *     R = |AB|·|BC|·|CA| / (4·S),   S = obsah trojúhelníku ABC.
 * Geometrie z OSM je lomená čára, proto se body nejdřív vyhladí klouzavým průměrem.
 *
 * Doporučená rychlost:   v_max = √(g · R · tan θ)
 * Potřebná vzdálenost:   d = v·t_r + (v² − v_max²) / (2·a)
 */
public final class Curves {
    public static final double G = 9.81;

    /** Jedna zatáčka na trase. */
    public static final class Curve {
        public double startS, endS, apexS;  // vzdálenosti od motorky [m]
        public double minR;                 // nejmenší poloměr [m]
        public int direction;               // +1 vlevo, -1 vpravo
        public boolean unpaved;             // nezpevněný povrch podle OSM
    }

    /** Doporučení pro nejbližší zatáčku. */
    public static final class Advice {
        public Curve curve;          // null = žádná zatáčka v dohledu
        public double distanceM;     // vzdálenost k začátku zatáčky (0 = už v ní)
        public double vMaxKmh;       // doporučená rychlost
        public double neededM;       // vzdálenost potřebná ke zpomalení
        public int level;            // 0 = OK, 1 = zpomal mírně, 2 = zpomal hodně
    }

    /** Vyhlazení klouzavým průměrem (okno 2·half+1 bodů). */
    public static double[] smooth(double[] v, int half) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            int a = Math.max(0, i - half), b = Math.min(v.length - 1, i + half);
            double sum = 0;
            for (int j = a; j <= b; j++) sum += v[j];
            out[i] = sum / (b - a + 1);
        }
        return out;
    }

    /** Poloměr kružnice přes 3 body. Znaménko: + zatáčka vlevo, − vpravo. ∞ = přímka. */
    public static double circleRadius(double ax, double ay, double bx, double by, double cx, double cy) {
        double ab = Math.hypot(bx - ax, by - ay), bc = Math.hypot(cx - bx, cy - by), ca = Math.hypot(ax - cx, ay - cy);
        double cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);  // = 2·S se znaménkem
        if (Math.abs(cross) < 1e-9) return Double.POSITIVE_INFINITY;
        return ab * bc * ca / (2 * cross);
    }

    /** Poloměry ve všech bodech trasy (vzdálenost sousedů k bodů). */
    public static double[] radii(RoadPath p, int smoothHalf, int k) {
        double[] xs = smooth(p.x, smoothHalf), ys = smooth(p.y, smoothHalf);
        double[] r = new double[xs.length];
        for (int i = 0; i < xs.length; i++) {
            if (i - k < 0 || i + k >= xs.length) {
                r[i] = Double.POSITIVE_INFINITY;
                continue;
            }
            r[i] = circleRadius(xs[i - k], ys[i - k], xs[i], ys[i], xs[i + k], ys[i + k]);
        }
        return r;
    }

    /** Najde souvislé úseky s |R| pod prahem (zatáčky) před motorkou. */
    public static List<Curve> find(RoadPath p, double[] r, double thresholdM, RoadNetwork net) {
        List<Curve> out = new ArrayList<>();
        Curve cur = null;
        for (int i = 0; i < r.length; i++) {
            boolean inCurve = Math.abs(r[i]) < thresholdM;
            // zatáčka musí mít stálý směr; změna směru (esíčko) = nová zatáčka
            if (inCurve && cur != null && (int) Math.signum(r[i]) != cur.direction) {
                out.add(cur);
                cur = null;
            }
            if (inCurve) {
                if (cur == null) {
                    cur = new Curve();
                    cur.startS = p.s[i];
                    cur.minR = Double.MAX_VALUE;
                    cur.direction = (int) Math.signum(r[i]);
                }
                cur.endS = p.s[i];
                if (Math.abs(r[i]) < cur.minR) {
                    cur.minR = Math.abs(r[i]);
                    cur.apexS = p.s[i];
                }
                if (net != null && p.way[i] >= 0 && net.ways.get(p.way[i]).unpaved()) cur.unpaved = true;
            } else if (cur != null) {
                out.add(cur);
                cur = null;
            }
        }
        if (cur != null) out.add(cur);
        // ponecháme jen zatáčky, které ještě neskončily za motorkou
        List<Curve> ahead = new ArrayList<>();
        for (Curve c : out) if (c.endS >= 0) ahead.add(c);
        return ahead;
    }

    /** v_max = √(g·R·tanθ) [m/s]. */
    public static double vMax(double radiusM, double leanDeg) {
        return Math.sqrt(G * radiusM * Math.tan(Math.toRadians(leanDeg)));
    }

    /** d = v·t_r + (v² − v_max²)/(2a); pokud v ≤ v_max, stačí reakční dráha. */
    public static double neededDistance(double v, double vMax, double reactionS, double brake) {
        return v * reactionS + Math.max(0, v * v - vMax * vMax) / (2 * brake);
    }

    /**
     * Vyhodnotí nejbližší nebezpečnou zatáčku.
     * @param lowGrip kamera/vibrace hlásí štěrk nebo mokro -> menší náklon
     */
    public static Advice advise(List<Curve> curves, double speedMps, boolean lowGrip, Settings s) {
        Advice best = new Advice();
        best.vMaxKmh = Double.NaN;
        for (Curve c : curves) {
            double lean = lowGrip || c.unpaved ? s.leanDegLowGrip : s.leanDeg;
            double vMax = vMax(c.minR, lean);
            double dist = Math.max(0, c.startS);
            double needed = neededDistance(speedMps, vMax, s.reactionS, s.brakeMps2);
            int level = 0;
            if (speedMps > vMax && dist <= needed + s.marginM) {
                double excessKmh = (speedMps - vMax) * 3.6;
                level = excessKmh >= 15 || dist <= speedMps * s.reactionS ? 2 : 1;
            }
            // vybereme první zatáčku; pokud je ale dál jiná s vyšším stupněm varování, má přednost
            if (best.curve == null || level > best.level) {
                best.curve = c;
                best.distanceM = dist;
                best.vMaxKmh = vMax * 3.6;
                best.neededM = needed;
                best.level = level;
            }
        }
        return best;
    }
}
