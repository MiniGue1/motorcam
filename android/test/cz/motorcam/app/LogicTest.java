package cz.motorcam.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Testy logiky aplikace na běžném JVM (bez telefonu): mapa, zatáčky, detekce, kontrolka.
 * Spuštění: python android/build.py --test
 */
public final class LogicTest {
    static int failures = 0;

    static void check(boolean ok, String msg) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + msg);
        if (!ok) failures++;
    }

    /** Syntetická síť: rovně na východ 500 m, zatáčka doleva R=50 m, rovně na sever;
     *  v x=200 odbočka na jih (T-křižovatka). */
    static String syntheticNetwork(Geo g) {
        StringBuilder nodes = new StringBuilder();
        List<Long> main = new ArrayList<>();
        long id = 1;
        List<double[]> pts = new ArrayList<>();
        for (int x = 0; x <= 500; x += 50) pts.add(new double[]{x, 0});
        double r = 50;
        for (int deg = 5; deg <= 90; deg += 5) {
            double f = Math.toRadians(deg);
            pts.add(new double[]{500 + r * Math.sin(f), r - r * Math.cos(f)});
        }
        for (int y = 100; y <= 400; y += 50) pts.add(new double[]{550, y});
        for (double[] p : pts) {
            nodes.append(String.format(Locale.US, "{\"type\":\"node\",\"id\":%d,\"lat\":%.8f,\"lon\":%.8f},", id, g.lat(p[1]), g.lon(p[0])));
            main.add(id++);
        }
        // odbočka na jih z uzlu x=200 (id 5)
        long side1 = id++, side2 = id++;
        nodes.append(String.format(Locale.US, "{\"type\":\"node\",\"id\":%d,\"lat\":%.8f,\"lon\":%.8f},", side1, g.lat(-100), g.lon(200)));
        nodes.append(String.format(Locale.US, "{\"type\":\"node\",\"id\":%d,\"lat\":%.8f,\"lon\":%.8f},", side2, g.lat(-300), g.lon(210)));
        StringBuilder mainIds = new StringBuilder();
        for (int i = 0; i < main.size(); i++) mainIds.append(i > 0 ? "," : "").append(main.get(i));
        return "{\"elements\":[" + nodes
                + "{\"type\":\"way\",\"id\":100,\"nodes\":[" + mainIds + "],\"tags\":{\"highway\":\"secondary\",\"name\":\"II/123\"}},"
                + "{\"type\":\"way\",\"id\":101,\"nodes\":[5," + side1 + "," + side2 + "],\"tags\":{\"highway\":\"track\"}}]}";
    }

    public static void main(String[] args) throws Exception {
        Settings s = new Settings();
        Geo g = new Geo(50, 15);
        RoadNetwork net = RoadNetwork.parseOverpass(syntheticNetwork(g), 50, 15);
        System.out.println("Mapa a zatáčky");
        check(net.ways.size() == 2 && net.ways.get(1).unpaved(), "načtení sítě z JSON Overpass");

        double east = Geo.bearingToAngle(90);
        RoadPath.Match m = RoadPath.match(net, 300, 3, east, 40);
        check(m != null && m.way == 0 && m.forward && Math.abs(m.distance - 3) < 0.5, "map matching na hlavní silnici, směr vpřed");
        RoadPath p = RoadPath.build(net, m, 300, 40, 5);
        check(Math.abs(p.s[p.s.length - 1] - 300) <= 5 && Math.abs(p.s[0] + 40) <= 5, "trasa 300 m dopředu a 40 m dozadu");
        check(Math.abs(p.x[p.x.length - 1] - 550) < 2 && p.y[p.y.length - 1] > 30, "na křižovatce pokračuje hlavní silnicí (ne odbočkou)");

        double[] r = Curves.radii(p, 3, 4);
        List<Curves.Curve> curves = Curves.find(p, r, s.radiusThresholdM, net);
        check(curves.size() == 1, "nalezena 1 zatáčka (nalezeno " + curves.size() + ")");
        Curves.Curve c = curves.get(0);
        System.out.printf(Locale.US, "        R_min=%.1f m, začátek %.0f m, vrchol %.0f m, směr %d%n", c.minR, c.startS, c.apexS, c.direction);
        check(Math.abs(c.minR - 50) < 12, "poloměr ~50 m");
        check(c.direction == 1, "zatáčka doleva");
        check(Math.abs(c.apexS - 239) < 30, "vrchol zatáčky ~240 m před motorkou");

        double vmax = Curves.vMax(50, 25) * 3.6;
        check(Math.abs(vmax - 54.4) < 0.5, String.format(Locale.US, "v_max(R=50, 25°) = %.1f km/h", vmax));
        Curves.Advice a = Curves.advise(curves, 90 / 3.6, false, s);
        check(a.level == 0, "90 km/h, 200 m před zatáčkou -> zatím bez varování (level " + a.level + ")");
        RoadPath.Match m2 = RoadPath.match(net, 440, 0, east, 40);
        RoadPath p2 = RoadPath.build(net, m2, 300, 40, 5);
        List<Curves.Curve> c2 = Curves.find(p2, Curves.radii(p2, 3, 4), s.radiusThresholdM, net);
        Curves.Advice a2 = Curves.advise(c2, 90 / 3.6, false, s);
        check(a2.level == 2, String.format(Locale.US, "90 km/h, %.0f m před zatáčkou -> rychlé pípání (level %d)", a2.distanceM, a2.level));
        Curves.Advice a3 = Curves.advise(c2, 60 / 3.6, false, s);
        check(a3.level == 0, "60 km/h, 55 m před zatáčkou -> ještě dost místa (potřeba " + Math.round(a3.neededM + s.marginM) + " m)");
        RoadPath.Match m3 = RoadPath.match(net, 470, 0, east, 40);
        RoadPath p3 = RoadPath.build(net, m3, 300, 40, 5);
        List<Curves.Curve> c3 = Curves.find(p3, Curves.radii(p3, 3, 4), s.radiusThresholdM, net);
        Curves.Advice a5 = Curves.advise(c3, 60 / 3.6, false, s);
        check(a5.level == 1, String.format(Locale.US, "60 km/h, %.0f m před zatáčkou -> mírné varování (level %d)", a5.distanceM, a5.level));
        Curves.Advice a4 = Curves.advise(c2, 60 / 3.6, true, s);
        check(a4.vMaxKmh < a3.vMaxKmh && a4.level == 2, String.format(Locale.US, "štěrk: v_max klesne na %.0f km/h", a4.vMaxKmh));

        RoadPath.Match mw = RoadPath.match(net, 300, 3, Geo.bearingToAngle(270), 40);
        RoadPath pw = RoadPath.build(net, mw, 300, 40, 5);
        check(!mw.forward && Curves.find(pw, Curves.radii(pw, 3, 4), s.radiusThresholdM, net).isEmpty(),
                "jízda na západ -> bez zatáčky");
        check(RoadPath.match(net, 300, 100, east, 40) == null, "mimo silnici -> bez zápasu");

        System.out.println("Detekce (YOLO)");
        // model 640x640, snímek 1280x720 -> scale 0.5, pad Y (640-360)/2 = 140
        float[][] out = new float[6][100];   // [4+nc][N], zbytek kandidátů = nuly
        float[][] vals = {{0.5f, 0.5f, 0.1f}, {0.7f, 0.7f, 0.1f}, {0.1f, 0.1f, 0.05f}, {0.1f, 0.1f, 0.05f},
                {0.9f, 0.6f, 0.1f}, {0.05f, 0.1f, 0.8f}};
        for (int k = 0; k < 6; k++) System.arraycopy(vals[k], 0, out[k], 0, 3);
        List<Detection> d = Detection.decodeYolo(out, 640, 0, 140, 0.5f, 1280, 720, 0.4f, 0.5f);
        check(d.size() == 2, "NMS sloučí překryté boxy (" + d.size() + " boxy)");
        Detection d0 = d.get(0);
        check(d0.cls == 0 && Math.abs((d0.x1 + d0.x2) / 2 - 0.5f) < 1e-3 && Math.abs((d0.y1 + d0.y2) / 2 - (448 - 140) / 360f) < 1e-3,
                "převod z letterboxu na souřadnice snímku");
        check(d0.inCorridor(), "díra uprostřed dole je v koridoru");

        System.out.println("Kontrolka");
        Fusion f = new Fusion(s);
        Fusion.Inputs in = new Fusion.Inputs();
        in.potholeClass = new boolean[]{true, false};
        in.speedKmh = 40;
        List<Detection> hole = new ArrayList<>();
        hole.add(new Detection(0.4f, 0.6f, 0.6f, 0.8f, 0, 0.9f));
        List<Detection> none = new ArrayList<>();
        long t = 1000;
        in.detections = none;
        for (int i = 0; i < 20; i++) f.update(t += 100, in);
        check(f.level == Fusion.GREEN, "bez objektů zelená");
        in.detections = hole;
        f.update(t += 100, in);
        in.detections = none;
        for (int i = 0; i < 5; i++) f.update(t += 100, in);
        check(f.level == Fusion.GREEN, "jednorázový záblesk (1 snímek) kontrolku nerozsvítí");
        in.detections = hole;
        for (int i = 0; i < 3; i++) f.update(t += 100, in);
        check(f.level == Fusion.RED, "díra v koridoru 0,3 s -> červená");
        in.detections = none;
        f.update(t += 100, in);
        f.update(t += 100, in);
        check(f.level == Fusion.RED, "po zmizení díry se červená drží (bez blikání)");
        for (int i = 0; i < 32; i++) f.update(t += 100, in);
        check(f.level == Fusion.GREEN, "po okně 1 s + držení 2 s zpět zelená");
        // povrch: štěrk ve vyšší rychlosti
        in.surfaceProbs = new float[]{0.05f, 0.05f, 0.85f, 0.03f, 0.02f};
        in.speedKmh = 70;
        for (int i = 0; i < 30; i++) f.update(t += 100, in);
        check(f.level == Fusion.RED && f.surface == Fusion.STERK, "štěrk v 70 km/h -> červená (" + f.reason + ")");
        in.speedKmh = 30;
        for (int i = 0; i < 60; i++) f.update(t += 100, in);
        check(f.level == Fusion.ORANGE, "štěrk v 30 km/h -> oranžová (" + f.reason + ")");
        // jeden chybný snímek s dírou mezi mnoha prázdnými se neprojeví
        in.surfaceProbs = new float[]{0.9f, 0.05f, 0.02f, 0.02f, 0.01f};
        for (int i = 0; i < 30; i++) f.update(t += 100, in);
        check(f.level == Fusion.ORANGE && f.reason.startsWith("změna povrchu"), "přechod na asfalt -> nejdřív „změna povrchu“");
        for (int i = 0; i < 60; i++) f.update(t += 100, in);
        check(f.level == Fusion.GREEN, "asfalt -> zelená");

        System.out.println("Vibrace");
        Vibration v = new Vibration(1.0);
        java.util.Random rnd = new java.util.Random(1);
        for (int i = 0; i < 100; i++) v.add(i * 0.02, rnd.nextGaussian() * 0.4, rnd.nextGaussian() * 0.4, 9.81 + rnd.nextGaussian() * 0.4);
        check(v.level(50, 0.8) == 0, String.format(Locale.US, "hladký asfalt (RMS %.2f) -> 0", v.rms));
        for (int i = 100; i < 200; i++) v.add(i * 0.02, rnd.nextGaussian() * 2, rnd.nextGaussian() * 2, 9.81 + rnd.nextGaussian() * 2);
        check(v.level(50, 0.8) == 2, String.format(Locale.US, "velké otřesy (RMS %.2f) -> 2", v.rms));
        check(v.level(3, 0.8) == -1, "při stání se neodhaduje");

        System.out.println(failures == 0 ? "\nVŠECHNY TESTY PROŠLY" : "\nSELHALO: " + failures);
        if (failures > 0) System.exit(1);
    }
}
