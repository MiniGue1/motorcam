package cz.motorcam.app;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Testy plánovače tras (JVM). Spuštění: python android/build.py --test */
public final class RouteTest {
    static int failures = 0;

    static void check(boolean ok, String msg) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + msg);
        if (!ok) failures++;
    }

    /** Lomená čára z bodů v metrech (x východ, y sever) -> lat/lon. */
    static double[][] toLatLon(Geo g, List<double[]> xy) {
        double[] lat = new double[xy.size()], lon = new double[xy.size()];
        for (int i = 0; i < xy.size(); i++) {
            lat[i] = g.lat(xy.get(i)[1]);
            lon[i] = g.lon(xy.get(i)[0]);
        }
        return new double[][]{lat, lon};
    }

    public static void main(String[] args) throws Exception {
        Geo g = new Geo(50, 17);

        System.out.println("Předpřipravené trasy (assets/trasy.json)");
        String json = new String(Files.readAllBytes(Paths.get(args.length > 0 ? args[0] : "assets/trasy.json")),
                StandardCharsets.UTF_8);
        Route.Library lib = Route.parseLibrary(json);
        check(lib.routes.size() >= 6, lib.routes.size() + " tras, " + lib.places.size() + " míst");
        boolean inRegion = true;
        for (Route.Waypoint w : lib.places.values()) {
            if (w.lat < 49.5 || w.lat > 50.5 || w.lon < 16.6 || w.lon > 17.7) {
                inRegion = false;
                System.out.println("        mimo region: " + w.name);
            }
        }
        check(inRegion, "všechna místa leží na Olomoucku / Jesenicku");
        double maxLeg = 0;
        String maxLegName = "";
        for (Route r : lib.routes) {
            double straight = 0;
            for (int i = 1; i < r.waypoints.size(); i++) {
                Route.Waypoint a = r.waypoints.get(i - 1), b = r.waypoints.get(i);
                double d = Geo.distance(a.lat, a.lon, b.lat, b.lon);
                straight += d;
                if (d > maxLeg) {
                    maxLeg = d;
                    maxLegName = r.id + ": " + a.name + " → " + b.name;
                }
            }
            System.out.printf(Locale.US, "        %-16s %2d bodů, vzdušnou čarou %.0f km%n", r.id, r.waypoints.size(), straight / 1000);
        }
        check(maxLeg < 40000, String.format(Locale.US, "nejdelší úsek mezi body %.1f km (%s) – překlep v souřadnicích by dal víc",
                maxLeg / 1000, maxLegName));

        System.out.println("Zatáčkovitost");
        List<double[]> straight = new ArrayList<>();
        for (int i = 0; i <= 200; i++) straight.add(new double[]{i * 25, 0});
        double[][] s = toLatLon(g, straight);
        Curviness.Stats st = Curviness.analyze(s[0], s[1]);
        check(st.total() == 0 && st.score < 1, String.format(Locale.US, "rovná silnice 5 km: %d zatáček, skóre %.0f", st.total(), st.score));

        // serpentiny: 6 vraceček R=18 m spojených 150m rovinkami
        List<double[]> serp = new ArrayList<>();
        double x = 0, y = 0;
        int dir = 1;
        for (int h = 0; h < 6; h++) {
            for (int i = 0; i < 30; i++) serp.add(new double[]{x + dir * i * 5, y});
            x += dir * 150;
            for (int a = 0; a <= 18; a++) {
                double f = Math.PI * a / 18;
                serp.add(new double[]{x + dir * 18 * Math.sin(f), y + 18 - 18 * Math.cos(f)});
            }
            y += 36;
            dir = -dir;
        }
        double[][] sp = toLatLon(g, serp);
        Curviness.Stats ss = Curviness.analyze(sp[0], sp[1]);
        check(ss.hairpins + ss.sharp >= 5, String.format(Locale.US, "serpentiny: vraceček %d, ostrých %d, skóre %.0f",
                ss.hairpins, ss.sharp, ss.score));
        check(ss.score > 80, "serpentiny mají vysoké skóre");

        // plynulé vlnovky (R ~ 200 m)
        List<double[]> wave = new ArrayList<>();
        for (int i = 0; i <= 1000; i++) {
            double t = i * 5;
            wave.add(new double[]{t, 60 * Math.sin(t / 110)});
        }
        double[][] wv = toLatLon(g, wave);
        Curviness.Stats sw = Curviness.analyze(wv[0], wv[1]);
        check(sw.gentle + sw.medium > 0 && sw.score > st.score && sw.score < ss.score,
                String.format(Locale.US, "vlnovka: plynulých %d, středních %d, skóre %.0f", sw.gentle, sw.medium, sw.score));

        System.out.println("Sledování trasy");
        // trasa: 500 m na východ, zatáčka doleva R=50 m, 300 m na sever
        List<double[]> path = new ArrayList<>();
        for (int i = 0; i <= 100; i++) path.add(new double[]{i * 5, 0});
        for (int d = 5; d <= 90; d += 5) {
            double f = Math.toRadians(d);
            path.add(new double[]{500 + 50 * Math.sin(f), 50 - 50 * Math.cos(f)});
        }
        for (int i = 1; i <= 60; i++) path.add(new double[]{550, 50 + i * 5});
        double[][] pl = toLatLon(g, path);
        RouteFollower rf = new RouteFollower(g, pl[0], pl[1]);
        double east = Geo.bearingToAngle(90);
        check(rf.update(440, 3, east) && Math.abs(rf.progressM - 440) < 3,
                String.format(Locale.US, "poloha na trase: ujeto %.0f m, odchylka %.1f m", rf.progressM, rf.distanceToRouteM));
        RoadPath ahead = rf.ahead(300, 40, 5);
        List<Curves.Curve> cs = Curves.find(ahead, Curves.radii(ahead, 3, 4), 150, null);
        check(cs.size() == 1 && Math.abs(cs.get(0).minR - 50) < 12 && Math.abs(cs.get(0).startS - 55) < 25,
                String.format(Locale.US, "zatáčka na trase: R=%.0f m za %.0f m", cs.isEmpty() ? 0 : cs.get(0).minR,
                        cs.isEmpty() ? 0 : cs.get(0).startS));
        check(Math.abs(rf.remainingM() - (rf.totalM() - 440)) < 3, String.format(Locale.US, "zbývá %.0f m", rf.remainingM()));
        check(!rf.update(440, 120, east), "120 m od trasy -> mimo trasu");

        // tam a zpět po stejné silnici: 1 km na východ a zpět
        List<double[]> tz = new ArrayList<>();
        for (int i = 0; i <= 200; i++) tz.add(new double[]{i * 5, 0});
        for (int i = 199; i >= 0; i--) tz.add(new double[]{i * 5, 0.5});
        double[][] tzl = toLatLon(g, tz);
        RouteFollower back = new RouteFollower(g, tzl[0], tzl[1]);
        for (int xx = 0; xx <= 1000; xx += 20) back.update(xx, 0, east);            // cesta tam
        double west = Geo.bearingToAngle(270);
        for (int xx = 1000; xx >= 300; xx -= 20) back.update(xx, 0, west);          // cesta zpět
        check(Math.abs(back.progressM - 1700) < 10,
                String.format(Locale.US, "tam a zpět: na zpáteční cestě v x=300 m ujeto %.0f m (čekám 1700)", back.progressM));

        System.out.println("Router (OSRM) a export");
        String osrm = "{\"code\":\"Ok\",\"routes\":[{\"distance\":1234.5,\"duration\":99.1,\"geometry\":{\"type\":"
                + "\"LineString\",\"coordinates\":[[17.1,50.1],[17.11,50.1],[17.12,50.105]]}},{\"distance\":2000,"
                + "\"duration\":150,\"geometry\":{\"coordinates\":[[17.1,50.1],[17.2,50.2]]}}]}";
        List<RoutingService.Candidate> cands = RoutingService.parse(osrm);
        check(cands.size() == 2 && cands.get(0).lat[2] == 50.105 && cands.get(0).lon[2] == 17.12
                && cands.get(0).distanceM == 1234.5, "parsování odpovědi OSRM (lon,lat -> lat,lon)");
        List<Route.Waypoint> two = new ArrayList<>();
        two.add(new Route.Waypoint("A", 49.5938, 17.2509));
        two.add(new Route.Waypoint("B", 50.2294, 17.2046));
        String url = RoutingService.buildUrl("https://x/route/v1/driving/", two, true, true);
        check(url.equals("https://x/route/v1/driving/17.250900,49.593800;17.204600,50.229400"
                + "?overview=full&geometries=geojson&steps=false&alternatives=3&exclude=motorway"), "URL požadavku: " + url);
        RoutingService.Candidate joined = RoutingService.concat(cands);
        check(joined.lat.length == 4 && joined.distanceM == 3234.5, "spojení úseků bez zdvojeného bodu");
        try {
            RoutingService.parse("{\"code\":\"NoRoute\",\"message\":\"Impossible route\"}");
            check(false, "chyba routeru se ohlásí");
        } catch (org.json.JSONException e) {
            check(e.getMessage().contains("NoRoute"), "chyba routeru se ohlásí: " + e.getMessage());
        }

        Route r = lib.routes.get(0);
        r.lat = pl[0];
        r.lon = pl[1];
        r.distanceM = Route.length(pl[0], pl[1]);
        String gpx = RouteExport.gpx(r);
        int trkpts = gpx.split("<trkpt").length - 1, rtepts = gpx.split("<rtept").length - 1;
        check(trkpts == pl[0].length && rtepts == r.waypoints.size() && gpx.contains("<name>Dvě sedla"),
                "GPX: " + rtepts + " průjezdních bodů, " + trkpts + " bodů stopy");
        String mapy = RouteExport.mapyCzUrl(r), google = RouteExport.googleMapsUrl(r);
        check(mapy.startsWith("https://mapy.com/fnc/v1/route?") && mapy.contains("&waypoints=")
                && mapy.split(";").length <= 15, "odkaz Mapy.cz (" + (mapy.split(";").length + 2) + " bodů)");
        String[] gw = google.split("&waypoints=")[1].split("%7C");
        check(google.contains("origin=49.965300,16.970600") && gw.length <= 9, "odkaz Google Maps (" + gw.length + " průjezdních bodů)");

        Route copy = Route.fromJson(new JSONObject(r.toJson().toString()));
        check(copy.waypoints.size() == r.waypoints.size() && copy.lat.length == r.lat.length
                && Math.abs(copy.distanceM - r.distanceM) < 1e-6, "uložení a načtení trasy (JSON)");

        System.out.println(failures == 0 ? "\nVŠECHNY TESTY TRAS PROŠLY" : "\nSELHALO: " + failures);
        if (failures > 0) System.exit(1);
    }
}
