package cz.motorcam.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Map matching (přiřazení polohy k silnici) a trasa před motorkou.
 *
 * Postup:
 *  1. match(): najde nejbližší úsek silnice, jehož směr odpovídá kurzu z GPS.
 *  2. build(): od toho bodu jde po silnici dopředu (a kousek dozadu). Na křižovatce
 *     pokračuje tou silnicí, která nejméně zatáčí (bez navigace nevíme, kam jezdec
 *     odbočí – předpokládáme hlavní směr). Výsledek převzorkuje po `step` metrech.
 */
public final class RoadPath {

    /** Výsledek map matchingu. */
    public static final class Match {
        public int way, seg;        // cesta a úsek (uzly seg -> seg+1)
        public double t;            // poloha na úseku 0..1
        public boolean forward;     // jede ve směru uzlů cesty?
        public double distance;     // kolmá vzdálenost od silnice [m]
        public double px, py;       // bod na silnici
    }

    public final double[] x, y, s;  // body trasy, s = vzdálenost od motorky (záporná = za ní)
    public final int[] way;         // index cesty OSM pro každý bod
    public final int zeroIdx;       // index bodu nejbližšího motorce

    RoadPath(double[] x, double[] y, double[] s, int[] way, int zeroIdx) {
        this.x = x;
        this.y = y;
        this.s = s;
        this.way = way;
        this.zeroIdx = zeroIdx;
    }

    /**
     * @param heading kurz v radiánech (matematický úhel), NaN = neznámý
     * @param maxDist maximální vzdálenost od silnice [m]
     */
    public static Match match(RoadNetwork net, double px, double py, double heading, double maxDist) {
        Match best = null;
        double bestCost = Double.MAX_VALUE;
        for (int w = 0; w < net.ways.size(); w++) {
            RoadNetwork.Way way = net.ways.get(w);
            int[] ns = way.nodes;
            for (int k = 0; k + 1 < ns.length; k++) {
                double ax = net.nx[ns[k]], ay = net.ny[ns[k]];
                double bx = net.nx[ns[k + 1]], by = net.ny[ns[k + 1]];
                // rychlé vyřazení vzdálených úseků
                if (Math.min(ax, bx) - maxDist > px || Math.max(ax, bx) + maxDist < px
                        || Math.min(ay, by) - maxDist > py || Math.max(ay, by) + maxDist < py) continue;
                double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
                double t = len2 > 0 ? ((px - ax) * dx + (py - ay) * dy) / len2 : 0;
                t = Math.max(0, Math.min(1, t));
                double qx = ax + t * dx, qy = ay + t * dy;
                double d = Math.hypot(px - qx, py - qy);
                if (d > maxDist) continue;
                double segAngle = Math.atan2(dy, dx);
                boolean forward = true;
                double cost = d;
                if (!Double.isNaN(heading)) {
                    double diffF = Math.abs(Geo.angleDiff(heading, segAngle));
                    double diffB = Math.abs(Geo.angleDiff(heading, segAngle + Math.PI));
                    if (way.oneway == 1) diffB = Double.MAX_VALUE;
                    if (way.oneway == -1) diffF = Double.MAX_VALUE;
                    forward = diffF <= diffB;
                    double diff = Math.min(diffF, diffB);
                    if (diff > Math.toRadians(100)) continue;   // jede proti jednosměrce / napříč
                    cost += 20 * diff;                           // 90° odchylky ~ +31 m
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    best = new Match();
                    best.way = w;
                    best.seg = k;
                    best.t = t;
                    best.forward = forward;
                    best.distance = d;
                    best.px = qx;
                    best.py = qy;
                }
            }
        }
        return best;
    }

    /** Postaví trasu: `ahead` metrů dopředu a `behind` metrů dozadu, převzorkováno po `step` m. */
    public static RoadPath build(RoadNetwork net, Match m, double ahead, double behind, double step) {
        List<double[]> fwd = walk(net, m, m.forward, ahead, false);
        List<double[]> back = walk(net, m, !m.forward, behind, true);
        // spojení: zadní část obráceně + přední část (bod zápasu je v obou -> jednou)
        List<double[]> pts = new ArrayList<>();
        for (int i = back.size() - 1; i >= 1; i--) pts.add(back.get(i));
        pts.addAll(fwd);
        int zero = back.size() - 1;

        // kumulativní vzdálenost, nula v bodě zápasu
        double[] cum = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) {
            cum[i] = cum[i - 1] + Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0], pts.get(i)[1] - pts.get(i - 1)[1]);
        }
        double offset = cum[zero];
        double start = -Math.floor(offset / step) * step;
        int n = (int) Math.floor((cum[cum.length - 1] - offset - start) / step) + 1;
        n = Math.max(n, 1);
        double[] xs = new double[n], ys = new double[n], ss = new double[n];
        int[] ws = new int[n];
        int j = 0, zeroIdx = 0;
        for (int i = 0; i < n; i++) {
            double target = start + i * step + offset;
            while (j + 1 < cum.length - 1 && cum[j + 1] < target) j++;
            double segLen = j + 1 < cum.length ? cum[j + 1] - cum[j] : 0;
            double t = segLen > 0 ? (target - cum[j]) / segLen : 0;
            t = Math.max(0, Math.min(1, t));
            double[] a = pts.get(j), b = pts.get(Math.min(j + 1, pts.size() - 1));
            xs[i] = a[0] + t * (b[0] - a[0]);
            ys[i] = a[1] + t * (b[1] - a[1]);
            ss[i] = start + i * step;
            ws[i] = (int) b[2];
            if (Math.abs(ss[i]) < Math.abs(ss[zeroIdx])) zeroIdx = i;
        }
        return new RoadPath(xs, ys, ss, ws, zeroIdx);
    }

    /**
     * Jde po silnici od bodu zápasu daným směrem, dokud neujde `distance` metrů.
     * Vrací body {x, y, index cesty}. První bod = bod zápasu.
     */
    static List<double[]> walk(RoadNetwork net, Match m, boolean forward, double distance, boolean ignoreOneway) {
        List<double[]> out = new ArrayList<>();
        int w = m.way;
        int dir = forward ? 1 : -1;
        int npos = forward ? m.seg + 1 : m.seg;      // pozice dalšího uzlu v cestě
        double cx = m.px, cy = m.py;
        out.add(new double[]{cx, cy, w});
        double remaining = distance;
        for (int guard = 0; guard < 20000 && remaining > 0; guard++) {
            int[] ns = net.ways.get(w).nodes;
            int node = ns[npos];
            double tx = net.nx[node], ty = net.ny[node];
            double len = Math.hypot(tx - cx, ty - cy);
            if (len >= remaining) {
                double t = remaining / len;
                out.add(new double[]{cx + t * (tx - cx), cy + t * (ty - cy), w});
                break;
            }
            remaining -= len;
            double inAngle = len > 0.01 ? Math.atan2(ty - cy, tx - cx) : Double.NaN;
            cx = tx;
            cy = ty;
            if (len > 0.01) out.add(new double[]{cx, cy, w});

            // výběr pokračování v uzlu: nejmenší zatočení, přednost má stejná silnice
            int bestW = -1, bestPos = -1, bestDir = 0;
            double bestScore = Double.MAX_VALUE;
            for (int[] wp : net.nodeWays.get(node)) {
                RoadNetwork.Way cand = net.ways.get(wp[0]);
                for (int d = -1; d <= 1; d += 2) {
                    int p2 = wp[1] + d;
                    if (p2 < 0 || p2 >= cand.nodes.length) continue;
                    if (!ignoreOneway && ((cand.oneway == 1 && d < 0) || (cand.oneway == -1 && d > 0))) continue;
                    if (wp[0] == w && wp[1] == npos && d == -dir) continue;   // zpět, odkud jedeme
                    int nextNode = cand.nodes[p2];
                    double ox = net.nx[nextNode] - cx, oy = net.ny[nextNode] - cy;
                    if (Math.hypot(ox, oy) < 0.01) continue;
                    double turn = Double.isNaN(inAngle) ? 0 : Math.abs(Geo.angleDiff(Math.atan2(oy, ox), inAngle));
                    if (turn > Math.toRadians(150)) continue;                 // otočka
                    double score = turn - (wp[0] == w ? 0.35 : 0);
                    if (score < bestScore) {
                        bestScore = score;
                        bestW = wp[0];
                        bestPos = p2;
                        bestDir = d;
                    }
                }
            }
            if (bestW < 0) break;   // slepá ulice
            w = bestW;
            npos = bestPos;
            dir = bestDir;
        }
        return out;
    }
}
