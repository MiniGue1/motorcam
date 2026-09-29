package cz.motorcam.app;

/**
 * Převod zeměpisných souřadnic na lokální rovinné souřadnice v metrech.
 * Na území pár kilometrů je chyba zanedbatelná (ekvidistantní válcová projekce).
 * Osa x = východ, osa y = sever.
 */
public final class Geo {
    public static final double EARTH_R = 6371000.0;

    public final double lat0, lon0;
    private final double kx, ky;

    public Geo(double lat0, double lon0) {
        this.lat0 = lat0;
        this.lon0 = lon0;
        this.ky = Math.toRadians(1) * EARTH_R;
        this.kx = ky * Math.cos(Math.toRadians(lat0));
    }

    public double x(double lon) { return (lon - lon0) * kx; }

    public double y(double lat) { return (lat - lat0) * ky; }

    public double lat(double y) { return lat0 + y / ky; }

    public double lon(double x) { return lon0 + x / kx; }

    /** Vzdálenost dvou bodů (haversine) v metrech. */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_R * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Úhel mezi dvěma směry v radiánech, výsledek v intervalu (-π, π]. */
    public static double angleDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d <= -Math.PI) d += 2 * Math.PI;
        return d;
    }

    /** Kurz z GPS (stupně od severu po směru hodin) -> matematický úhel (rad od osy x proti směru hodin). */
    public static double bearingToAngle(double bearingDeg) {
        return Math.toRadians(90 - bearingDeg);
    }
}
