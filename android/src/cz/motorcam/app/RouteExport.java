package cz.motorcam.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Export trasy: GPX soubor a odkazy pro navigaci v Mapy.cz / Google Maps. */
public final class RouteExport {

    /** GPX 1.1: průjezdní body jako <rte>, spočítaná geometrie jako <trk>. */
    public static String gpx(Route r) {
        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"MotorCam\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                .append("  <metadata><name>").append(esc(r.name)).append("</name><desc>").append(esc(r.description))
                .append("</desc></metadata>\n  <rte><name>").append(esc(r.name)).append("</name>\n");
        for (Route.Waypoint w : r.waypoints) {
            b.append(String.format(Locale.US, "    <rtept lat=\"%.6f\" lon=\"%.6f\"><name>%s</name></rtept>\n",
                    w.lat, w.lon, esc(w.name)));
        }
        b.append("  </rte>\n");
        if (r.hasGeometry()) {
            b.append("  <trk><name>").append(esc(r.name)).append("</name><trkseg>\n");
            for (int i = 0; i < r.lat.length; i++) {
                b.append(String.format(Locale.US, "    <trkpt lat=\"%.6f\" lon=\"%.6f\"/>\n", r.lat[i], r.lon[i]));
            }
            b.append("  </trkseg></trk>\n");
        }
        return b.append("</gpx>\n").toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Body pro externí navigaci: průjezdní body, u okruhu doplněný návrat na start, max. `max` bodů. */
    static List<Route.Waypoint> navPoints(Route r, int max) {
        List<Route.Waypoint> pts = new ArrayList<>(r.waypoints);
        if ("okruh".equals(r.type) && pts.size() >= 2) {
            Route.Waypoint a = pts.get(0), z = pts.get(pts.size() - 1);
            if (Geo.distance(a.lat, a.lon, z.lat, z.lon) > 50) pts.add(a);
        }
        if (pts.size() <= max) return pts;
        // rovnoměrný výběr, první a poslední bod vždy
        List<Route.Waypoint> out = new ArrayList<>();
        for (int i = 0; i < max; i++) out.add(pts.get(Math.round((float) i * (pts.size() - 1) / (max - 1))));
        return out;
    }

    /** Mapy.cz (Mapy.com URL API): start, cíl a až 15 průjezdních bodů. */
    public static String mapyCzUrl(Route r) {
        List<Route.Waypoint> p = navPoints(r, 17);
        StringBuilder b = new StringBuilder("https://mapy.com/fnc/v1/route?mapset=outdoor&routeType=car_fast");
        b.append(String.format(Locale.US, "&start=%.6f,%.6f", p.get(0).lon, p.get(0).lat));
        b.append(String.format(Locale.US, "&end=%.6f,%.6f", p.get(p.size() - 1).lon, p.get(p.size() - 1).lat));
        if (p.size() > 2) {
            b.append("&waypoints=");
            for (int i = 1; i < p.size() - 1; i++) {
                if (i > 1) b.append(';');
                b.append(String.format(Locale.US, "%.6f,%.6f", p.get(i).lon, p.get(i).lat));
            }
        }
        return b.toString();
    }

    /** Google Maps (Maps URLs): start, cíl a až 9 průjezdních bodů. */
    public static String googleMapsUrl(Route r) {
        List<Route.Waypoint> p = navPoints(r, 11);
        StringBuilder b = new StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=driving");
        b.append(String.format(Locale.US, "&origin=%.6f,%.6f", p.get(0).lat, p.get(0).lon));
        b.append(String.format(Locale.US, "&destination=%.6f,%.6f", p.get(p.size() - 1).lat, p.get(p.size() - 1).lon));
        if (p.size() > 2) {
            b.append("&waypoints=");
            for (int i = 1; i < p.size() - 1; i++) {
                if (i > 1) b.append("%7C");
                b.append(String.format(Locale.US, "%.6f,%.6f", p.get(i).lat, p.get(i).lon));
            }
        }
        return b.toString();
    }
}
