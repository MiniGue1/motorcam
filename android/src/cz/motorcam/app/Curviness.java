package cz.motorcam.app;

/**
 * Hodnocení „zatáčkovitosti“ trasy – kolik a jak ostrých zatáček obsahuje.
 *
 * Geometrie se převzorkuje po 5 m, vyhladí a v každém bodě se spočítá poloměr kružnice
 * přes body ±20 m (stejně jako při varování před zatáčkami, viz Curves). Souvislé úseky
 * s poloměrem pod 300 m jsou zatáčky; podle nejmenšího poloměru se třídí na
 *   vracečky (R < 25 m), ostré (< 60 m), střední (< 150 m) a plynulé (< 300 m).
 * Skóre 0–100: vážený počet zatáček na km (vracečka 4, ostrá 3, střední 2, plynulá 1)
 * převedený křivkou 100·(1 − e^(−x/3)) – rovná dálnice ≈ 0, horské sedlo ≈ 80+.
 */
public final class Curviness {
    public static final double CURVE_R = 300;

    public static final class Stats {
        public double lengthM;
        public int hairpins, sharp, medium, gentle;
        public double curvyM;      // délka úseků v zatáčkách
        public double score;       // 0–100

        public int total() {
            return hairpins + sharp + medium + gentle;
        }
    }

    public static Stats analyze(double[] lat, double[] lon) {
        Stats st = new Stats();
        if (lat == null || lat.length < 2) return st;
        Geo g = new Geo(lat[0], lon[0]);
        int n = lat.length;
        double[] x = new double[n], y = new double[n], cum = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = g.x(lon[i]);
            y[i] = g.y(lat[i]);
            if (i > 0) cum[i] = cum[i - 1] + Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
        }
        st.lengthM = cum[n - 1];
        double step = 5;
        int m = (int) (st.lengthM / step) + 1;
        if (m < 12) return st;
        double[] rx = new double[m], ry = new double[m];
        int j = 0;
        for (int i = 0; i < m; i++) {
            double t = i * step;
            while (j + 1 < n - 1 && cum[j + 1] < t) j++;
            double seg = cum[j + 1] - cum[j];
            double f = seg > 0 ? Math.max(0, Math.min(1, (t - cum[j]) / seg)) : 0;
            rx[i] = x[j] + f * (x[j + 1] - x[j]);
            ry[i] = y[j] + f * (y[j + 1] - y[j]);
        }
        double[] sx = Curves.smooth(rx, 3), sy = Curves.smooth(ry, 3);
        int k = 4;
        double minR = Double.MAX_VALUE;
        int dir = 0, len = 0;
        for (int i = k; i < m - k; i++) {
            double r = Curves.circleRadius(sx[i - k], sy[i - k], sx[i], sy[i], sx[i + k], sy[i + k]);
            boolean in = Math.abs(r) < CURVE_R;
            int d = (int) Math.signum(r);
            if (len > 0 && (!in || d != dir)) {
                classify(st, minR, len * step);
                len = 0;
                minR = Double.MAX_VALUE;
            }
            if (in) {
                if (len == 0) dir = d;
                len++;
                minR = Math.min(minR, Math.abs(r));
            }
        }
        if (len > 0) classify(st, minR, len * step);
        double km = Math.max(0.1, st.lengthM / 1000);
        double weighted = (4.0 * st.hairpins + 3.0 * st.sharp + 2.0 * st.medium + st.gentle) / km;
        st.score = 100 * (1 - Math.exp(-weighted / 3));
        return st;
    }

    private static void classify(Stats st, double minR, double lengthM) {
        if (lengthM < 10) return;   // šum na krátkém úseku
        st.curvyM += lengthM;
        if (minR < 25) st.hairpins++;
        else if (minR < 60) st.sharp++;
        else if (minR < 150) st.medium++;
        else st.gentle++;
    }
}
