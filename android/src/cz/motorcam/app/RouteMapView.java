package cz.motorcam.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.Locale;

/**
 * Náhled trasy: čára obarvená podle ostrosti zatáček, průjezdní body s názvy, start/cíl
 * a měřítko. Posun prstem, zoom dvěma prsty. Bez mapového podkladu (dlaždice OSM nelze
 * v aplikaci hromadně používat) – body obcí slouží k orientaci.
 */
public final class RouteMapView extends View {
    private Route route;
    private double[] x, y, r;          // trasa v metrech + poloměry
    private double[] wx, wy;           // průjezdní body
    private double cx, cy, scale = 1;  // střed pohledu [m] a měřítko [px/m]
    private final float dp;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaler;
    private final GestureDetector panner;
    public String message;             // text místo trasy (např. „Počítám trasu…“)

    public RouteMapView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(Color.WHITE);
        text.setTextSize(12 * dp);
        text.setShadowLayer(3 * dp, 0, 0, Color.BLACK);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                scale = Math.max(1e-4, Math.min(2, scale * d.getScaleFactor()));
                invalidate();
                return true;
            }
        });
        panner = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                cx += dx / scale;
                cy -= dy / scale;
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                fit();
                invalidate();
                return true;
            }
        });
    }

    public void setRoute(Route route) {
        this.route = route;
        x = y = r = null;
        wx = wy = null;
        if (route == null || route.waypoints.isEmpty()) {
            invalidate();
            return;
        }
        Route.Waypoint o = route.waypoints.get(0);
        Geo g = new Geo(o.lat, o.lon);
        int nw = route.waypoints.size();
        wx = new double[nw];
        wy = new double[nw];
        for (int i = 0; i < nw; i++) {
            wx[i] = g.x(route.waypoints.get(i).lon);
            wy[i] = g.y(route.waypoints.get(i).lat);
        }
        if (route.hasGeometry()) {
            // převzorkování po 10 m kvůli rychlosti kreslení a poloměrům
            RouteFollower f = new RouteFollower(g, route.lat, route.lon);
            f.progressM = 0;
            RoadPath p = f.ahead(f.totalM(), 0, 10);
            x = p.x;
            y = p.y;
            r = Curves.radii(p, 2, 3);
        }
        fit();
        invalidate();
    }

    private void fit() {
        double[] xs = x != null ? x : wx, ys = x != null ? y : wy;
        if (xs == null || getWidth() == 0) return;
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }
        cx = (minX + maxX) / 2;
        cy = (minY + maxY) / 2;
        double pad = 40 * dp;
        scale = Math.min((getWidth() - 2 * pad) / Math.max(100, maxX - minX), (getHeight() - 2 * pad) / Math.max(100, maxY - minY));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        fit();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        panner.onTouchEvent(e);
        return true;
    }

    private float sx(double v) {
        return (float) (getWidth() / 2.0 + (v - cx) * scale);
    }

    private float sy(double v) {
        return (float) (getHeight() / 2.0 - (v - cy) * scale);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Color.rgb(15, 23, 42));
        if (route == null) return;
        if (x != null) {
            // podklad (tmavší okraj) a pak barva podle poloměru
            line.setStrokeWidth(7 * dp);
            line.setColor(Color.argb(200, 0, 0, 0));
            for (int i = 1; i < x.length; i++) c.drawLine(sx(x[i - 1]), sy(y[i - 1]), sx(x[i]), sy(y[i]), line);
            line.setStrokeWidth(4 * dp);
            for (int i = 1; i < x.length; i++) {
                double rr = Math.abs(r[i]);
                line.setColor(rr < 25 ? OverlayView.C_RED : rr < 60 ? OverlayView.C_ORANGE
                        : rr < 150 ? Color.rgb(250, 204, 21) : Color.rgb(148, 163, 184));
                c.drawLine(sx(x[i - 1]), sy(y[i - 1]), sx(x[i]), sy(y[i]), line);
            }
        } else if (wx != null) {
            line.setStrokeWidth(2 * dp);
            line.setColor(Color.argb(150, 148, 163, 184));
            for (int i = 1; i < wx.length; i++) c.drawLine(sx(wx[i - 1]), sy(wy[i - 1]), sx(wx[i]), sy(wy[i]), line);
        }
        // body a popisky; popisek se nesmí překrýt s jiným – zkusí se vpravo, pak vlevo, jinak se vynechá
        java.util.List<android.graphics.RectF> placed = new java.util.ArrayList<>();
        for (int i = 0; i < wx.length; i++) {
            boolean end = i == 0 || i == wx.length - 1;
            fill.setColor(end ? OverlayView.C_GREEN : Color.WHITE);
            float px = sx(wx[i]), py = sy(wy[i]);
            c.drawCircle(px, py, (end ? 7 : 5) * dp, fill);
            placed.add(new android.graphics.RectF(px - 6 * dp, py - 6 * dp, px + 6 * dp, py + 6 * dp));
        }
        for (int i = 0; i < wx.length; i++) {
            boolean closingLoop = i == wx.length - 1 && Geo.distance(route.waypoints.get(0).lat,
                    route.waypoints.get(0).lon, route.waypoints.get(i).lat, route.waypoints.get(i).lon) < 100;
            if (closingLoop) continue;
            String name = route.waypoints.get(i).name;
            float w = text.measureText(name), h = text.getTextSize(), px = sx(wx[i]), py = sy(wy[i]);
            android.graphics.RectF right = new android.graphics.RectF(px + 9 * dp, py - 6 * dp - h, px + 9 * dp + w, py - 3 * dp);
            android.graphics.RectF left = new android.graphics.RectF(px - 9 * dp - w, py - 6 * dp - h, px - 9 * dp, py - 3 * dp);
            android.graphics.RectF chosen = free(right, placed) ? right : free(left, placed) ? left : null;
            if (chosen == null) continue;
            placed.add(chosen);
            c.drawText(name, chosen.left, chosen.bottom - 3 * dp, text);
        }
        drawScale(c);
        drawLegend(c);
        if (message != null) {
            text.setTextSize(15 * dp);
            float w = text.measureText(message);
            fill.setColor(Color.argb(210, 0, 0, 0));
            c.drawRoundRect(getWidth() / 2f - w / 2 - 14 * dp, 8 * dp, getWidth() / 2f + w / 2 + 14 * dp, 40 * dp,
                    10 * dp, 10 * dp, fill);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(message, getWidth() / 2f, 30 * dp, text);
            text.setTextSize(12 * dp);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private static boolean free(android.graphics.RectF r, java.util.List<android.graphics.RectF> placed) {
        for (android.graphics.RectF p : placed) if (android.graphics.RectF.intersects(r, p)) return false;
        return true;
    }

    private void drawScale(Canvas c) {
        double target = 100 * dp / scale;   // ~100 dp
        double[] nice = {100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000};
        double len = nice[0];
        for (double n : nice) if (n <= target) len = n;
        float px = (float) (len * scale), x0 = 12 * dp, y0 = getHeight() - 14 * dp;
        line.setColor(Color.WHITE);
        line.setStrokeWidth(2 * dp);
        c.drawLine(x0, y0, x0 + px, y0, line);
        c.drawText(len >= 1000 ? String.format(Locale.US, "%.0f km", len / 1000) : String.format(Locale.US, "%.0f m", len),
                x0, y0 - 6 * dp, text);
    }

    private void drawLegend(Canvas c) {
        if (x == null) return;
        String[] labels = {"vracečka", "ostrá", "střední"};
        int[] colors = {OverlayView.C_RED, OverlayView.C_ORANGE, Color.rgb(250, 204, 21)};
        float xx = getWidth() - 90 * dp, yy = 18 * dp;
        for (int i = 0; i < labels.length; i++) {
            fill.setColor(colors[i]);
            c.drawRect(xx, yy + i * 16 * dp - 8 * dp, xx + 14 * dp, yy + i * 16 * dp - 2 * dp, fill);
            c.drawText(labels[i], xx + 20 * dp, yy + i * 16 * dp, text);
        }
    }
}
