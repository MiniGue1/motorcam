package cz.motorcam.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Plánovač tras v Robolectricu: seznam, detail, editor, vedení po trase + screenshoty. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlannerSmokeTest {

    static List<View> all(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) all(((ViewGroup) v).getChildAt(i), out);
        return out;
    }

    static View withText(View root, String prefix) {
        for (View v : all(root, new ArrayList<View>())) {
            if (v instanceof TextView && ((TextView) v).getText().toString().startsWith(prefix)) return v;
        }
        return null;
    }

    static View clickableParent(View v) {
        while (v != null && !v.hasOnClickListeners()) v = (View) v.getParent();
        return v;
    }

    static void shot(View root, String name) throws Exception {
        String dir = System.getProperty("shotdir");
        if (dir == null) return;
        root.measure(View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1233, 2673);
        Bitmap b = Bitmap.createBitmap(1233, 2673, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        FileOutputStream out = new FileOutputStream(dir + "/" + name + ".png");
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();
    }

    void run(double s) {
        ShadowLooper.idleMainLooper((long) (s * 1000), TimeUnit.MILLISECONDS);
    }

    @Test
    public void planovac() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);
        RouteStore store = new RouteStore(app);
        byte[] raw = MapService.readAll(app.getAssets().open("trasy.json"));
        System.out.println("   assets/trasy.json: " + raw.length + " B, začátek: " + new String(raw, 0, Math.min(60, raw.length), "UTF-8").replace("\n", " "));
        Route.Library dbg = Route.parseLibrary(new String(raw, "UTF-8"));
        System.out.println("   parse: " + dbg.routes.size() + " tras, " + dbg.places.size() + " míst");
        assertEquals("načtení tras: " + store.loadError, 7, store.library().routes.size());

        ActivityController<PlannerActivity> ctl = Robolectric.buildActivity(PlannerActivity.class).setup();
        PlannerActivity a = ctl.get();
        View root = a.getWindow().getDecorView();
        for (Route r : store.library().routes) assertNotNull("karta " + r.name, withText(root, r.name));
        System.out.println("1) seznam: 7 předpřipravených tras");
        shot(root, "planovac_seznam");

        // detail předpřipravené trasy (geometrie jen uměle – router tu není dostupný)
        clickableParent(withText(root, "Dvě sedla")).performClick();
        run(0.5);
        assertNotNull(withText(root, "okruh"));
        assertNotNull(withText(root, "Šumperk → Velké Losiny"));
        System.out.println("2) detail trasy (bez sítě se trasa nespočítá – test počítá s chybou)");
        run(3);
        shot(root, "planovac_detail");
        a.onBackPressed();
        run(0.2);

        // editor: 2 místa z tipů
        clickableParent(withText(root, "+ Naplánovat")).performClick();
        run(0.2);
        for (int k = 0; k < 2; k++) {
            ((Button) withText(root, "⭐ Tipy")).performClick();
            run(0.2);
            AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
            shadowOf(d).clickOnItem(k == 0 ? 0 : 5);
            run(0.2);
        }
        assertNotNull(withText(root, "1. Olomouc"));
        assertNotNull(withText(root, "2. Šternberk"));
        System.out.println("3) editor: přidány 2 body (Olomouc, Šternberk)");
        shot(root, "planovac_editor");
        ((Button) withText(root, "Spočítat trasu")).performClick();
        run(1);
        assertEquals(1, store.userRoutes().size());
        System.out.println("4) vlastní trasa uložena");

        // aktivní trasa s geometrií -> hlavní obrazovka, simulace po trase
        Route r = store.library().routes.get(0);
        Geo g = new Geo(49.9653, 16.9706);
        int n = 400;
        r.lat = new double[n];
        r.lon = new double[n];
        for (int i = 0; i < n; i++) {           // 2 km rovně na sever, pak zatáčka doprava R≈60 m, rovně na východ
            double x, y;
            if (i < 200) { x = 0; y = i * 10; }
            else if (i < 220) { double f = Math.PI / 2 * (i - 200) / 20.0; x = 60 - 60 * Math.cos(f); y = 2000 + 60 * Math.sin(f); }
            else { x = 60 + (i - 220) * 10; y = 2060; }
            r.lat[i] = g.lat(y);
            r.lon[i] = g.lon(x);
        }
        r.distanceM = Route.length(r.lat, r.lon);
        store.save(r);
        store.setActive(r.id);
        ActivityController<MainActivity> mc = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity m = mc.get();
        run(0.5);
        List<View> views = all(m.getWindow().getDecorView(), new ArrayList<View>());
        Button sim = null;
        for (View v : views) if (v instanceof Button && ((Button) v).getText().toString().equals("SIM")) sim = (Button) v;
        assertNotNull(sim);
        assertNotNull("tlačítko TRASY", withText(m.getWindow().getDecorView(), "TRASY"));
        sim.performClick();
        boolean warned = false;
        String info = null;
        for (int i = 0; i < 100; i++) {          // 100 × 0,1 s při 80 km/h ≈ 220 m
            run(0.1);
        }
        for (int i = 0; i < 1500 && !warned; i++) {
            run(0.1);
            Field f = MainActivity.class.getDeclaredField("advice");
            f.setAccessible(true);
            Curves.Advice adv = (Curves.Advice) f.get(m);
            if (adv != null && adv.level > 0) warned = true;
        }
        Field ov = MainActivity.class.getDeclaredField("overlay");
        ov.setAccessible(true);
        Field stf = OverlayView.class.getDeclaredField("st");
        stf.setAccessible(true);
        OverlayView.State st = (OverlayView.State) stf.get(ov.get(m));
        info = st.routeInfo;
        System.out.println("5) simulace po trase: " + info + ", varování před zatáčkou: " + warned);
        assertTrue(info != null && info.contains("do cíle"));
        assertTrue("varování před zatáčkou R≈60 m při 80 km/h", warned);
        mc.pause().stop().destroy();
        ctl.pause().stop().destroy();
        System.out.println("6) bez pádu");
    }
}
