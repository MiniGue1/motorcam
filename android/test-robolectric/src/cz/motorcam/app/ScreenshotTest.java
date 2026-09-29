package cz.motorcam.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Vykreslí ukázkovou obrazovku (umělá silnice + skutečný overlay aplikace) do PNG. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, qualifiers = "w900dp-h405dp-land-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenshotTest {
    @Test
    public void screenshot() throws Exception {
        int w = 2400, h = 1080;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // obloha, tráva, silnice
        p.setShader(new LinearGradient(0, 0, 0, h * 0.45f, Color.rgb(120, 170, 220), Color.rgb(200, 220, 235), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.45f, p);
        p.setShader(null);
        p.setColor(Color.rgb(90, 130, 70));
        c.drawRect(0, h * 0.45f, w, h, p);
        Path road = new Path();
        road.moveTo(w * 0.47f, h * 0.45f); road.lineTo(w * 0.53f, h * 0.45f);
        road.lineTo(w * 0.95f, h); road.lineTo(w * 0.05f, h); road.close();
        p.setColor(Color.rgb(85, 85, 90));
        c.drawPath(road, p);
        p.setColor(Color.WHITE);
        for (int i = 0; i < 6; i++) {
            float y0 = h * (0.47f + i * 0.09f), y1 = y0 + h * 0.04f;
            c.drawRect(w * 0.5f - 3 - i * 2, y0, w * 0.5f + 3 + i * 2, y1, p);
        }
        p.setColor(Color.rgb(45, 40, 38));
        c.drawOval(w * 0.40f, h * 0.70f, w * 0.52f, h * 0.80f, p);   // díra
        c.drawOval(w * 0.66f, h * 0.55f, w * 0.70f, h * 0.58f, p);   // menší díra dál vpravo

        // overlay aplikace se syntetickou mapou (zatáčka před motorkou)
        Geo g = new Geo(50, 15);
        RoadNetwork net = RoadNetwork.parseOverpass(LogicTest.syntheticNetwork(g), 50, 15);
        Settings s = new Settings();
        RoadPath.Match m = RoadPath.match(net, 430, 0, 0, 40);
        RoadPath path = RoadPath.build(net, m, 340, 40, 5);
        double[] r = Curves.radii(path, 3, 4);
        List<Curves.Curve> curves = Curves.find(path, r, s.radiusThresholdM, net);
        OverlayView ov = new OverlayView(RuntimeEnvironment.getApplication());
        OverlayView.State st = new OverlayView.State();
        List<Detection> dets = new ArrayList<>();
        dets.add(new Detection(0.40f, 0.70f, 0.52f, 0.80f, 0, 0.87f));
        dets.add(new Detection(0.655f, 0.545f, 0.705f, 0.585f, 0, 0.64f));
        st.detections = dets;
        st.detNames = Models.DEFAULT_DET_NAMES;
        st.pothole = new boolean[]{true, false};
        st.surfaceText = "rozbitý asfalt 78 %";
        st.surfaceSource = "kamera";
        st.lamp = Fusion.RED;
        st.reason = "DÍRA PŘED MOTORKOU";
        st.speedKmh = 72;
        st.advice = Curves.advise(curves, 72 / 3.6, false, s);
        st.net = net;
        st.path = path;
        st.radii = r;
        st.posX = 430;
        st.posY = 0;
        st.heading = 0;
        float[] trail = new float[2 * 400];
        for (int i = 0; i < 40; i++) { trail[2 * i] = 40 + i * 10; trail[2 * i + 1] = 0; }
        st.trail = trail;
        st.trailLen = 40;
        st.settings = s;
        st.status = "GPS ±4 m · Mapa: 214 silnic · díry: model · povrch: model · 11.8 FPS · ● REC 03:12";
        ov.measure(w, h);
        ov.layout(0, 0, w, h);
        ov.setState(st);
        ov.draw(c);
        FileOutputStream out = new FileOutputStream(System.getProperty("shot"));
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();
    }
}
