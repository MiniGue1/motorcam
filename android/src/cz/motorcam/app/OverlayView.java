package cz.motorcam.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * Průhledná vrstva nad náhledem kamery:
 *  - rámečky detekcí s popiskem „díra 87 %“ (barva podle závažnosti),
 *  - povrch celého snímku v levém horním rohu („štěrk 92 %“),
 *  - kontrolka (zelená / oranžová / červená) s důvodem,
 *  - panel: rychlost, doporučená rychlost a vzdálenost k zatáčce,
 *  - minimapa: silnice, trasa, poloha motorky, zatáčky obarvené podle nebezpečnosti,
 *  - stavový řádek (GPS, mapa, modely, FPS).
 */
public final class OverlayView extends View {
    public static final int C_GREEN = Color.rgb(34, 197, 94), C_ORANGE = Color.rgb(245, 158, 11),
            C_RED = Color.rgb(239, 68, 68), C_BLUE = Color.rgb(59, 130, 246);

    /** Vše, co se má vykreslit (plní MainActivity). */
    public static final class State {
        public List<Detection> detections;
        public String[] detNames;
        public boolean[] pothole;
        public String surfaceText = "povrch: —", surfaceSource = "";
        public int lamp;
        public String reason = "";
        public double speedKmh = Double.NaN;
        public Curves.Advice advice;
        public String mapStatus = "";
        public String status = "";
        public String banner;             // důležitá zpráva uprostřed (např. chybí povolení)
        public RoadNetwork net;
        public RoadPath path;
        public double[] radii;
        public double posX, posY, heading = Double.NaN;
        public float[] trail;             // x0,y0,x1,y1,... (kruhový buffer)
        public int trailStart, trailLen;
        public Settings settings;
        public boolean lowGrip;
    }

    private State st = new State();
    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path bike = new Path();

    public OverlayView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setShadowLayer(3 * dp, 0, 0, Color.BLACK);
    }

    public void setState(State s) {
        st = s;
        invalidate();
    }

    private static int lampColor(int level) {
        return level == Fusion.RED ? C_RED : level == Fusion.ORANGE ? C_ORANGE : C_GREEN;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        drawDetections(c, w, h);
        drawSurface(c);
        drawLamp(c, w);
        drawPanel(c, h);
        drawMinimap(c, w, h);
        // stavový řádek
        text.setTextSize(12 * dp);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(st.status, w / 2f, h - 8 * dp, text);
        if (st.banner != null) {
            text.setTextSize(18 * dp);
            fill.setColor(Color.argb(200, 0, 0, 0));
            float tw = text.measureText(st.banner);
            rect.set(w / 2f - tw / 2 - 16 * dp, h / 2f - 30 * dp, w / 2f + tw / 2 + 16 * dp, h / 2f + 14 * dp);
            c.drawRoundRect(rect, 12 * dp, 12 * dp, fill);
            c.drawText(st.banner, w / 2f, h / 2f, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawDetections(Canvas c, int w, int h) {
        if (st.detections == null) return;
        text.setTextSize(14 * dp);
        stroke.setStrokeWidth(3 * dp);
        for (Detection d : st.detections) {
            boolean hole = st.pothole != null && d.cls < st.pothole.length && st.pothole[d.cls];
            int color = hole ? (d.inCorridor() ? C_RED : C_ORANGE) : Color.rgb(250, 204, 21);
            stroke.setColor(color);
            rect.set(d.x1 * w, d.y1 * h, d.x2 * w, d.y2 * h);
            c.drawRect(rect, stroke);
            String name = st.detNames != null && d.cls < st.detNames.length ? st.detNames[d.cls] : "objekt";
            String label = String.format(Locale.US, "%s %d %%", name, Math.round(d.conf * 100));
            float tw = text.measureText(label);
            fill.setColor(color);
            float top = Math.max(0, rect.top - 20 * dp);
            c.drawRect(rect.left, top, rect.left + tw + 8 * dp, top + 20 * dp, fill);
            c.drawText(label, rect.left + 4 * dp, top + 15 * dp, text);
        }
    }

    private void drawSurface(Canvas c) {
        text.setTextSize(22 * dp);
        float tw = text.measureText(st.surfaceText);
        fill.setColor(Color.argb(150, 0, 0, 0));
        rect.set(10 * dp, 10 * dp, 26 * dp + Math.max(tw, 110 * dp), 64 * dp);
        c.drawRoundRect(rect, 10 * dp, 10 * dp, fill);
        c.drawText(st.surfaceText, 18 * dp, 38 * dp, text);
        text.setTextSize(11 * dp);
        text.setColor(Color.rgb(200, 200, 200));
        c.drawText(st.surfaceSource, 18 * dp, 56 * dp, text);
        text.setColor(Color.WHITE);
    }

    private void drawLamp(Canvas c, int w) {
        float r = 34 * dp, cx = w - 22 * dp - r, cy = 16 * dp + r;
        fill.setColor(Color.argb(150, 0, 0, 0));
        c.drawCircle(cx, cy, r + 6 * dp, fill);
        fill.setColor(lampColor(st.lamp));
        c.drawCircle(cx, cy, r, fill);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(2 * dp);
        c.drawCircle(cx, cy, r, stroke);
        text.setTextSize(14 * dp);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(st.reason, w - 16 * dp, cy + r + 26 * dp, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawPanel(Canvas c, int h) {
        float left = 10 * dp, top = h - 132 * dp;
        fill.setColor(Color.argb(160, 0, 0, 0));
        rect.set(left, top, left + 250 * dp, h - 26 * dp);
        c.drawRoundRect(rect, 12 * dp, 12 * dp, fill);
        text.setTextSize(40 * dp);
        String speed = Double.isNaN(st.speedKmh) ? "--" : String.valueOf(Math.round(st.speedKmh));
        c.drawText(speed, left + 12 * dp, top + 44 * dp, text);
        float sw = text.measureText(speed);
        text.setTextSize(14 * dp);
        c.drawText("km/h", left + 18 * dp + sw, top + 44 * dp, text);

        Curves.Advice a = st.advice;
        String l1, l2;
        int color = Color.WHITE;
        if (a == null) {
            l1 = st.mapStatus;
            l2 = "";
        } else if (a.curve == null) {
            l1 = "Žádná ostrá zatáčka";
            l2 = String.format(Locale.US, "v dalších %.0f m", st.settings != null ? st.settings.lookaheadM : 300);
        } else {
            l1 = a.distanceM <= 0 ? String.format(Locale.US, "V zatáčce  R %.0f m", a.curve.minR)
                    : String.format(Locale.US, "Zatáčka za %.0f m  R %.0f m %s", a.distanceM, a.curve.minR,
                    a.curve.direction > 0 ? "↰" : "↱");
            l2 = String.format(Locale.US, "Doporučeno: %.0f km/h%s", a.vMaxKmh, st.lowGrip || a.curve.unpaved ? " (štěrk/mokro)" : "");
            color = a.level == 2 ? C_RED : a.level == 1 ? C_ORANGE : Color.WHITE;
        }
        text.setTextSize(15 * dp);
        c.drawText(l1, left + 12 * dp, top + 70 * dp, text);
        text.setColor(color);
        c.drawText(l2, left + 12 * dp, top + 92 * dp, text);
        text.setColor(Color.WHITE);
    }

    // --- minimapa (natočená po směru jízdy: nahoře = dopředu) ---
    private float mapCx, mapCy, mapScale, rotCos = 1, rotSin;

    private float mx(double x, double y) {
        double dx = x - st.posX, dy = y - st.posY;
        return (float) (mapCx + (dx * rotCos - dy * rotSin) * mapScale);
    }

    private float my(double x, double y) {
        double dx = x - st.posX, dy = y - st.posY;
        return (float) (mapCy - (dx * rotSin + dy * rotCos) * mapScale);
    }

    private void drawMinimap(Canvas c, int w, int h) {
        float size = Math.min(190 * dp, h * 0.45f);
        float left = w - size - 10 * dp, top = h - size - 26 * dp;
        fill.setColor(Color.argb(170, 15, 23, 42));
        rect.set(left, top, left + size, top + size);
        c.drawRoundRect(rect, 12 * dp, 12 * dp, fill);
        if (st.net == null) {
            text.setTextSize(12 * dp);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("mapa", left + size / 2, top + size / 2, text);
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }
        c.save();
        c.clipRect(rect);
        mapCx = left + size / 2;
        mapCy = top + size * 0.72f;
        double range = st.settings != null ? st.settings.lookaheadM : 300;
        mapScale = (float) (size * 0.66 / range);
        double heading = Double.isNaN(st.heading) ? Math.PI / 2 : st.heading;
        double rot = Math.PI / 2 - heading;
        rotCos = (float) Math.cos(rot);
        rotSin = (float) Math.sin(rot);
        double reach = range * 1.6;

        // okolní silnice
        line.setColor(Color.argb(140, 148, 163, 184));
        line.setStrokeWidth(2 * dp);
        RoadNetwork net = st.net;
        for (RoadNetwork.Way way : net.ways) {
            int[] ns = way.nodes;
            for (int k = 0; k + 1 < ns.length; k++) {
                double ax = net.nx[ns[k]], ay = net.ny[ns[k]], bx = net.nx[ns[k + 1]], by = net.ny[ns[k + 1]];
                if (Math.abs(ax - st.posX) > reach && Math.abs(bx - st.posX) > reach) continue;
                if (Math.abs(ay - st.posY) > reach && Math.abs(by - st.posY) > reach) continue;
                c.drawLine(mx(ax, ay), my(ax, ay), mx(bx, by), my(bx, by), line);
            }
        }
        // ujetá trasa
        if (st.trail != null && st.trailLen > 1) {
            line.setColor(C_BLUE);
            line.setStrokeWidth(3 * dp);
            int n = st.trail.length / 2;
            for (int i = 1; i < st.trailLen; i++) {
                int a = (st.trailStart + i - 1) % n, b = (st.trailStart + i) % n;
                c.drawLine(mx(st.trail[2 * a], st.trail[2 * a + 1]), my(st.trail[2 * a], st.trail[2 * a + 1]),
                        mx(st.trail[2 * b], st.trail[2 * b + 1]), my(st.trail[2 * b], st.trail[2 * b + 1]), line);
            }
        }
        // trasa před motorkou obarvená podle nebezpečnosti zatáček
        RoadPath p = st.path;
        if (p != null && st.radii != null && st.settings != null) {
            line.setStrokeWidth(5 * dp);
            double v = Double.isNaN(st.speedKmh) ? 0 : st.speedKmh / 3.6;
            double lean = st.lowGrip ? st.settings.leanDegLowGrip : st.settings.leanDeg;
            for (int i = Math.max(1, p.zeroIdx); i < p.x.length; i++) {
                double r = Math.abs(st.radii[i]);
                int color = Color.WHITE;
                if (r < st.settings.radiusThresholdM) {
                    double vmax = Curves.vMax(r, lean);
                    color = v > vmax ? C_RED : v > 0.8 * vmax ? C_ORANGE : Color.rgb(250, 204, 21);
                }
                line.setColor(color);
                c.drawLine(mx(p.x[i - 1], p.y[i - 1]), my(p.x[i - 1], p.y[i - 1]), mx(p.x[i], p.y[i]), my(p.x[i], p.y[i]), line);
            }
        }
        c.restore();
        // motorka (šipka nahoru)
        bike.reset();
        bike.moveTo(mapCx, mapCy - 10 * dp);
        bike.lineTo(mapCx - 7 * dp, mapCy + 8 * dp);
        bike.lineTo(mapCx, mapCy + 4 * dp);
        bike.lineTo(mapCx + 7 * dp, mapCy + 8 * dp);
        bike.close();
        fill.setColor(C_BLUE);
        c.drawPath(bike, fill);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(1.5f * dp);
        c.drawPath(bike, stroke);
    }
}
