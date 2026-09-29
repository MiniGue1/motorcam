package cz.motorcam.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ActivitySmokeTest {

    static Object get(Object o, String f) throws Exception {
        Field fl = o.getClass().getDeclaredField(f);
        fl.setAccessible(true);
        return fl.get(o);
    }

    static void set(Object o, String f, Object v) throws Exception {
        Field fl = o.getClass().getDeclaredField(f);
        fl.setAccessible(true);
        fl.set(o, v);
    }

    static List<Button> buttons(View v, List<Button> out) {
        if (v instanceof Button) out.add((Button) v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) buttons(((ViewGroup) v).getChildAt(i), out);
        return out;
    }

    void run(double seconds) {
        ShadowLooper.idleMainLooper((long) (seconds * 1000), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    void assertNoErrorToast() {
        String last = ShadowToast.getTextOfLatestToast();
        if (last != null) System.out.println("    poslední toast: " + last);
        assertFalse("chyba v tick(): " + last, last != null && last.startsWith("Chyba"));
    }

    Location gps(Geo g, double x, double y, float bearing, float speedMps) {
        Location l = new Location(LocationManager.GPS_PROVIDER);
        l.setLatitude(g.lat(y));
        l.setLongitude(g.lon(x));
        l.setBearing(bearing);
        l.setSpeed(speedMps);
        l.setAccuracy(4);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return l;
    }

    @Test
    public void celaAplikace() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();
        System.out.println("1) spuštěno");
        run(1);
        assertNoErrorToast();

        // mapa: syntetická síť (Overpass tu není dostupné)
        Geo g = new Geo(50, 15);
        RoadNetwork net = RoadNetwork.parseOverpass(LogicTest.syntheticNetwork(g), 50, 15);
        set(a, "net", net);
        set(a, "netLat", 50.0);
        set(a, "netLon", 15.0);
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);

        // jízda na východ 90 km/h směrem k zatáčce
        for (int i = 0; i < 60; i++) {
            double x = 300 + i * 2.5;
            shadowOf(lm).simulateLocation(gps(g, x, 0, 90, 25f));
            run(0.1);
        }
        Curves.Advice adv = (Curves.Advice) get(a, "advice");
        assertNotNull("advice", adv);
        assertNotNull("zatáčka nalezena", adv.curve);
        System.out.printf("2) GPS jízda: zatáčka za %.0f m, R=%.0f, v_max=%.0f, level %d%n", adv.distanceM, adv.curve.minR, adv.vMaxKmh, adv.level);
        assertEquals(2, adv.level);
        Fusion f = (Fusion) get(a, "fusion");
        System.out.println("   kontrolka " + f.level + " – " + f.reason);
        assertEquals(Fusion.RED, f.level);
        assertNoErrorToast();

        // vykreslení overlaye do bitmapy
        OverlayView ov = (OverlayView) get(a, "overlay");
        ov.layout(0, 0, 2400, 1080);
        Bitmap bmp = Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888);
        ov.draw(new Canvas(bmp));
        System.out.println("3) overlay vykreslen");

        // tlačítka
        List<Button> bs = buttons(a.getWindow().getDecorView(), new ArrayList<Button>());
        assertEquals(3, bs.size());
        Button rec = bs.get(0), sim = bs.get(1), menu = bs.get(2);

        rec.performClick();
        run(0.5);
        for (int i = 0; i < 10; i++) {
            shadowOf(lm).simulateLocation(gps(g, 400 + i * 2, 0, 90, 20f));
            run(0.1);
        }
        assertEquals("■ STOP", rec.getText().toString());
        rec.performClick();
        run(1.0);
        assertEquals("● REC", rec.getText().toString());
        System.out.println("4) REC/STOP ok");

        sim.performClick();
        run(0.2);
        assertEquals("■ SIM", sim.getText().toString());
        run(20);   // 20 s simulace 80 km/h ~ 440 m -> projede zatáčkou
        double lat = (Double) get(a, "curLat");
        System.out.printf("5) simulace: poloha x=%.0f y=%.0f, kontrolka %d (%s)%n", g.x((Double) get(a, "curLon")), g.y(lat), f.level, f.reason);
        assertTrue("simulace dojela za zatáčku na sever", g.y(lat) > 100);
        sim.performClick();
        assertNoErrorToast();

        menu.performClick();
        run(0.2);
        Method ms = MainActivity.class.getDeclaredMethod("showSettings");
        ms.setAccessible(true);
        ms.invoke(a);
        Method mh = MainActivity.class.getDeclaredMethod("showHelp");
        mh.setAccessible(true);
        mh.invoke(a);
        Method mc = MainActivity.class.getDeclaredMethod("startCalibration");
        mc.setAccessible(true);
        mc.invoke(a);
        run(11);
        System.out.println("6) menu, nastavení, nápověda, kalibrace ok");
        assertNoErrorToast();

        ctl.pause().stop().destroy();
        System.out.println("7) ukončeno bez pádu");
    }
}
