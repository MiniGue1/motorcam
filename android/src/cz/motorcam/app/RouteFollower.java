package cz.motorcam.app;

/**
 * Sledování naplánované trasy za jízdy.
 *
 * Místo hádání na křižovatkách (RoadPath.walk) se „trasa před motorkou“ vezme přímo
 * z naplánované geometrie. Poloha se hledá v okně kolem posledního nalezeného místa,
 * takže funguje i na trasách, které jedou po stejné silnici tam i zpět.
 */
public final class RouteFollower {
    public static final double OFF_ROUTE_M = 50;

    private final double[] x, y, cum;   // převzorkovaná trasa v lokálních souřadnicích [m]
    public final Geo geo;
    private int lastIdx = -1;

    public double progressM;            // ujetá vzdálenost podél trasy
    public double distanceToRouteM = Double.NaN;

    public RouteFollower(Geo geo, double[] lat, double[] lon) {
        this.geo = geo;
        int n = lat.length;
        double[] px = new double[n], py = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = geo.x(lon[i]);
            py[i] = geo.y(lat[i]);
            if (i > 0) c[i] = c[i - 1] + Math.hypot(px[i] - px[i - 1], py[i] - py[i - 1]);
        }
        x = px;
        y = py;
        cum = c;
    }

    public double totalM() {
        return cum[cum.length - 1];
    }

    public double remainingM() {
        return Math.max(0, totalM() - progressM);
    }

    public double[] xs() {
        return x;
    }

    public double[] ys() {
        return y;
    }

    public boolean onRoute() {
        return !Double.isNaN(distanceToRouteM) && distanceToRouteM <= OFF_ROUTE_M;
    }

    /**
     * Najde polohu na trase. heading (rad) pomáhá při prvním hledání rozlišit směr.
     * @return true, pokud je motorka na trase (do OFF_ROUTE_M)
     */
    public boolean update(double px, double py, double heading) {
        int from = 0, to = x.length - 2;
        boolean local = lastIdx >= 0;
        if (local) {
            from = Math.max(0, lastIdx - 40);
            to = Math.min(x.length - 2, lastIdx + 600);
        }
        int best = search(px, py, heading, from, to);
        if (best < 0 && local) best = search(px, py, heading, 0, x.length - 2);   // ztracená poloha
        if (best < 0) {
            distanceToRouteM = Double.NaN;
            return false;
        }
        lastIdx = best;
        return onRoute();
    }

    private int search(double px, double py, double heading, int from, int to) {
        int best = -1;
        double bestCost = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            double dx = x[i + 1] - x[i], dy = y[i + 1] - y[i], len2 = dx * dx + dy * dy;
            double t = len2 > 0 ? ((px - x[i]) * dx + (py - y[i]) * dy) / len2 : 0;
            t = Math.max(0, Math.min(1, t));
            double qx = x[i] + t * dx, qy = y[i] + t * dy;
            double d = Math.hypot(px - qx, py - qy);
            if (d > 300) continue;
            double cost = d;
            if (!Double.isNaN(heading) && len2 > 0) {
                cost += 20 * Math.abs(Geo.angleDiff(heading, Math.atan2(dy, dx)));   // opačný směr = +63 m
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = i;
                distanceToRouteM = d;
                progressM = cum[i] + t * Math.sqrt(len2);
            }
        }
        return best;
    }

    /** Bod trasy ve vzdálenosti s od startu: {x, y, směr v rad}. */
    public double[] pointAt(double s) {
        s = Math.max(0, Math.min(totalM(), s));
        int j = java.util.Arrays.binarySearch(cum, s);
        if (j < 0) j = -j - 2;
        j = Math.max(0, Math.min(cum.length - 2, j));
        double seg = cum[j + 1] - cum[j];
        double f = seg > 0 ? (s - cum[j]) / seg : 0;
        return new double[]{x[j] + f * (x[j + 1] - x[j]), y[j] + f * (y[j + 1] - y[j]),
                Math.atan2(y[j + 1] - y[j], x[j + 1] - x[j])};
    }

    /** Úsek trasy od (progress − behind) do (progress + ahead), převzorkovaný po step m. */
    public RoadPath ahead(double aheadM, double behindM, double step) {
        double start = Math.max(0, progressM - behindM), end = Math.min(totalM(), progressM + aheadM);
        int n = Math.max(1, (int) ((end - start) / step) + 1);
        double[] px = new double[n], py = new double[n], s = new double[n];
        int[] way = new int[n];
        int j = 0, zero = 0;
        for (int i = 0; i < n; i++) {
            double t = start + i * step;
            while (j + 1 < cum.length - 1 && cum[j + 1] < t) j++;
            double seg = cum[j + 1] - cum[j];
            double f = seg > 0 ? Math.max(0, Math.min(1, (t - cum[j]) / seg)) : 0;
            px[i] = x[j] + f * (x[j + 1] - x[j]);
            py[i] = y[j] + f * (y[j + 1] - y[j]);
            s[i] = t - progressM;
            way[i] = -1;
            if (Math.abs(s[i]) < Math.abs(s[zero])) zero = i;
        }
        return new RoadPath(px, py, s, way, zero);
    }
}
