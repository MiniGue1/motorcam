package cz.motorcam.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plánovač motorkářských tras:
 *   seznam (předpřipravené trasy Olomoucko + Jesenicko, vlastní trasy),
 *   detail (náhled, délka, čas, počet a ostrost zatáček, navigace, GPX),
 *   editor (průjezdní body, okruh, bez dálnic, zatáčkový režim).
 */
public class PlannerActivity extends Activity {
    private static final int C_BG = Color.rgb(15, 23, 42), C_CARD = Color.rgb(30, 41, 59),
            C_MUTED = Color.rgb(148, 163, 184), C_ACCENT = Color.rgb(59, 130, 246);

    private RouteStore store;
    private LinearLayout root;
    private int screen;                 // 0 seznam, 1 detail, 2 editor
    private Route current;
    private volatile boolean computing;
    private final Map<String, Curviness.Stats> statsCache = new HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        setTheme(android.R.style.Theme_DeviceDefault_NoActionBar);
        super.onCreate(b);
        CrashHandler.install(this);
        store = new RouteStore(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        setContentView(root);
        showList();
    }

    @Override
    public void onBackPressed() {
        if (screen == 2 && current != null && current.hasGeometry()) showDetail(current);
        else if (screen != 0) showList();
        else super.onBackPressed();
    }

    // ====================================================================== pomocné prvky

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_ACCENT);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setOnClickListener(l);
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        return p;
    }

    private LinearLayout header(String title, boolean back) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        Button b = button(back ? "←" : "✕", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        bar.addView(b, lp(dp(48), dp(44), 0));
        TextView t = text(title, 19, Color.WHITE, true);
        t.setSingleLine(true);
        bar.addView(t, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return bar;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(12));
        c.setBackground(bg);
        return c;
    }

    private void toast(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(PlannerActivity.this, s, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String km(double m) {
        return String.format(Locale.US, "%.0f km", m / 1000);
    }

    private static String duration(double s) {
        int min = (int) Math.round(s / 60);
        return min >= 60 ? String.format(Locale.US, "%d h %02d min", min / 60, min % 60) : min + " min";
    }

    private String summary(Route r) {
        StringBuilder b = new StringBuilder(r.type.equals("okruh") ? "okruh" : "tam");
        if (r.hasGeometry()) b.append(" · ").append(km(r.distanceM)).append(" · ").append(duration(r.durationS));
        else {
            double d = 0;
            for (int i = 1; i < r.waypoints.size(); i++) {
                d += Geo.distance(r.waypoints.get(i - 1).lat, r.waypoints.get(i - 1).lon, r.waypoints.get(i).lat,
                        r.waypoints.get(i).lon);
            }
            b.append(" · ~").append(km(d * 1.3));
        }
        if (!r.difficulty.isEmpty()) b.append(" · ").append(r.difficulty);
        return b.toString();
    }

    // ====================================================================== seznam

    private void showList() {
        screen = 0;
        current = null;
        root.removeAllViews();
        root.addView(header("Trasy pro motorkáře", false));
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), 0, dp(10), dp(20));
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        box.addView(button("+ Naplánovat vlastní trasu", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Route r = new Route();
                r.name = "Moje trasa";
                showEditor(r);
            }
        }), lp(-1, dp(50), 0));

        final Route active = store.find(store.activeId());
        if (active != null) {
            LinearLayout c = card();
            c.addView(text("Právě jedeš: " + active.name, 15, Color.WHITE, true));
            c.addView(button("Ukončit vedení po trase", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    store.setActive(null);
                    showList();
                }
            }), lp(-1, dp(44), 0));
            box.addView(c, lp(-1, -2, 0));
        }

        box.addView(sectionTitle("Olomoucko a Jesenicko – tipy na výlet"));
        if (store.library().routes.isEmpty() && store.loadError != null) {
            box.addView(text("Předpřipravené trasy nejdou načíst: " + store.loadError, 13, OverlayView.C_RED, false));
        }
        for (Route r : store.library().routes) box.addView(routeCard(r), lp(-1, -2, 0));
        List<Route> mine = store.userRoutes();
        if (!mine.isEmpty()) {
            box.addView(sectionTitle("Moje trasy"));
            for (Route r : mine) box.addView(routeCard(r), lp(-1, -2, 0));
        }
        TextView note = text("Trasy nejsou ověřené v terénu – před jízdou zkontroluj uzavírky. Silnice počítá "
                + "router OSRM z dat © přispěvatelé OpenStreetMap.", 12, C_MUTED, false);
        note.setPadding(dp(6), dp(12), dp(6), 0);
        box.addView(note);
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 14, C_MUTED, true);
        t.setPadding(dp(6), dp(16), dp(6), dp(4));
        return t;
    }

    private View routeCard(final Route r) {
        LinearLayout c = card();
        c.addView(text(r.name, 16, Color.WHITE, true));
        c.addView(text((r.region.isEmpty() ? "" : r.region + " · ") + summary(r), 13, C_MUTED, false));
        if (!r.description.isEmpty()) {
            String d = r.description.length() > 140 ? r.description.substring(0, 137) + "…" : r.description;
            TextView t = text(d, 13, Color.rgb(203, 213, 225), false);
            t.setPadding(0, dp(4), 0, 0);
            c.addView(t);
        }
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDetail(r);
            }
        });
        return c;
    }

    // ====================================================================== detail

    private void showDetail(final Route r) {
        screen = 1;
        current = r;
        root.removeAllViews();
        root.addView(header(r.name, true));
        final RouteMapView map = new RouteMapView(this);
        map.setRoute(r);
        root.addView(map, new LinearLayout.LayoutParams(-1, 0, 1.1f));

        ScrollView sv = new ScrollView(this);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(16));
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        fillDetail(box, r);

        if (!r.hasGeometry()) {
            map.message = "Počítám trasu…";
            compute(r, map, box);
        }
    }

    private void fillDetail(LinearLayout box, final Route r) {
        box.removeAllViews();
        box.addView(text(summary(r), 15, Color.WHITE, true));
        if (r.hasGeometry()) {
            Curviness.Stats st = statsCache.get(r.id);
            if (st == null) {
                st = Curviness.analyze(r.lat, r.lon);
                statsCache.put(r.id, st);
            }
            box.addView(text(String.format(Locale.US, "Zatáčky: %d  (vraceček %d, ostrých %d, středních %d, plynulých %d)",
                    st.total(), st.hairpins, st.sharp, st.medium, st.gentle), 14, Color.WHITE, false));
            box.addView(text(String.format(Locale.US, "Zatáčkovitost %.0f / 100 · v zatáčkách %.0f %% trasy",
                    st.score, 100 * st.curvyM / Math.max(1, st.lengthM)), 14, Color.rgb(250, 204, 21), true));
        }
        StringBuilder via = new StringBuilder();
        for (int i = 0; i < r.waypoints.size(); i++) via.append(i > 0 ? " → " : "").append(r.waypoints.get(i).name);
        TextView v = text(via.toString(), 13, C_MUTED, false);
        v.setPadding(0, dp(6), 0, dp(4));
        box.addView(v);
        if (!r.description.isEmpty()) box.addView(text(r.description, 14, Color.rgb(203, 213, 225), false));

        LinearLayout row1 = new LinearLayout(this);
        row1.addView(button("▶ Jet s MotorCam", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!r.hasGeometry()) {
                    toast("Trasa ještě není spočítaná");
                    return;
                }
                store.setActive(r.id);
                Intent i = new Intent(PlannerActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                finish();
            }
        }), lp(0, dp(48), 1));
        box.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.addView(button("Mapy.cz", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                open(RouteExport.mapyCzUrl(r));
            }
        }), lp(0, dp(44), 1));
        row2.addView(button("Google Maps", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                open(RouteExport.googleMapsUrl(r));
            }
        }), lp(0, dp(44), 1));
        row2.addView(button("GPX", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exportGpx(r);
            }
        }), lp(0, dp(44), 1));
        box.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.addView(button(r.builtin ? "Upravit kopii" : "Upravit", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Route copy = r;
                if (r.builtin) {
                    copy = new Route();
                    copy.name = r.name + " (upraveno)";
                    copy.type = r.type;
                    copy.description = r.description;
                    copy.region = r.region;
                    copy.waypoints.addAll(r.waypoints);
                    if ("okruh".equals(copy.type) && copy.waypoints.size() > 2) {
                        Route.Waypoint a = copy.waypoints.get(0), z = copy.waypoints.get(copy.waypoints.size() - 1);
                        if (Geo.distance(a.lat, a.lon, z.lat, z.lon) < 50) copy.waypoints.remove(copy.waypoints.size() - 1);
                    }
                }
                showEditor(copy);
            }
        }), lp(0, dp(44), 1));
        if (!r.builtin) {
            row3.addView(button("Smazat", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    new AlertDialog.Builder(PlannerActivity.this).setMessage("Smazat trasu " + r.name + "?")
                            .setPositiveButton("Smazat", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    store.delete(r);
                                    showList();
                                }
                            }).setNegativeButton("Zpět", null).show();
                }
            }), lp(0, dp(44), 1));
        }
        box.addView(row3);
        TextView hint = text("Mapy.cz a Google Maps trasu přepočítají po svém podle průjezdních bodů – "
                + "pro navigaci zatáčku po zatáčce. MotorCam mezitím hlídá zatáčky a povrch.", 12, C_MUTED, false);
        hint.setPadding(0, dp(8), 0, 0);
        box.addView(hint);
    }

    private void compute(final Route r, final RouteMapView map, final LinearLayout box) {
        if (computing) return;
        computing = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    RoutingService.compute(r, new RoutingService.Progress() {
                        @Override
                        public void onProgress(final String message) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    map.message = message;
                                    map.invalidate();
                                }
                            });
                        }
                    });
                    store.save(r);
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final String err = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        computing = false;
                        if (current != r || screen != 1) return;
                        if (err != null) {
                            map.message = "Trasu se nepodařilo spočítat";
                            map.invalidate();
                            toast("Chyba: " + err + " (je připojení k internetu?)");
                        } else {
                            statsCache.remove(r.id);
                            map.message = null;
                            map.setRoute(r);
                            fillDetail(box, r);
                        }
                    }
                });
            }
        }, "trasa");
        t.start();
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Nelze otevřít: " + e.getMessage());
        }
    }

    private void exportGpx(final Route r) {
        final String name = r.name.replaceAll("[^A-Za-z0-9áčďéěíňóřšťúůýžÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ _-]", "").trim() + ".gpx";
        final byte[] data = RouteExport.gpx(r).getBytes(StandardCharsets.UTF_8);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml");
                        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MotorCam");
                        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                        OutputStream out = getContentResolver().openOutputStream(uri);
                        out.write(data);
                        out.close();
                        toast("Uloženo: Stažené/MotorCam/" + name);
                    } else {
                        File dir = getExternalFilesDir("trasy");
                        File f = new File(dir, name);
                        MapService.writeAll(f, data);
                        toast("Uloženo: " + f.getAbsolutePath());
                    }
                } catch (Exception e) {
                    toast("GPX se nepodařilo uložit: " + e.getMessage());
                }
            }
        }).start();
    }

    // ====================================================================== editor

    private void showEditor(final Route r) {
        screen = 2;
        current = r;
        root.removeAllViews();
        root.addView(header(r.id == null ? "Nová trasa" : "Upravit trasu", true));
        ScrollView sv = new ScrollView(this);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), 0, dp(12), dp(16));
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        box.addView(text("Název", 13, C_MUTED, false));
        final EditText name = new EditText(this);
        name.setText(r.name);
        name.setSingleLine(true);
        box.addView(name);

        box.addView(sectionTitle("Průjezdní body"));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);
        refreshWaypoints(list, r);

        LinearLayout add = new LinearLayout(this);
        add.addView(button("📍 Moje poloha", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Location l = lastLocation();
                if (l == null) {
                    toast("Poloha zatím není známá – zapni GPS a otevři hlavní obrazovku");
                    return;
                }
                r.waypoints.add(new Route.Waypoint("Moje poloha", l.getLatitude(), l.getLongitude()));
                refreshWaypoints(list, r);
            }
        }), lp(0, dp(44), 1));
        add.addView(button("🔍 Hledat", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchDialog(list, r);
            }
        }), lp(0, dp(44), 1));
        add.addView(button("⭐ Tipy", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                placesDialog(list, r);
            }
        }), lp(0, dp(44), 1));
        box.addView(add);

        final CheckBox loop = new CheckBox(this), noMotorway = new CheckBox(this), curvy = new CheckBox(this);
        loop.setText("Okruh (na konci zpět na start)");
        loop.setChecked("okruh".equals(r.type));
        noMotorway.setText("Vyhnout se dálnicím");
        noMotorway.setChecked(r.avoidMotorway);
        curvy.setText("Zatáčkový režim – z variant vybrat nejzatáčkovější (pomalejší výpočet)");
        curvy.setChecked(r.curvy);
        for (CheckBox c : new CheckBox[]{loop, noMotorway, curvy}) {
            c.setTextColor(Color.WHITE);
            box.addView(c);
        }

        box.addView(button("Spočítat trasu", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (r.waypoints.size() < 2) {
                    toast("Přidej aspoň 2 body (start a cíl)");
                    return;
                }
                r.name = name.getText().toString().trim().isEmpty() ? "Moje trasa" : name.getText().toString().trim();
                r.type = loop.isChecked() ? "okruh" : "tam";
                r.avoidMotorway = noMotorway.isChecked();
                r.curvy = curvy.isChecked();
                r.lat = r.lon = null;
                r.builtin = false;
                try {
                    store.save(r);     // přidělí id
                } catch (Exception e) {
                    toast("Nelze uložit: " + e.getMessage());
                }
                showDetail(r);
            }
        }), lp(-1, dp(50), 0));
    }

    private void refreshWaypoints(final LinearLayout list, final Route r) {
        list.removeAllViews();
        if (r.waypoints.isEmpty()) {
            list.addView(text("Zatím žádné body – přidej start, zastávky a cíl.", 13, C_MUTED, false));
        }
        for (int i = 0; i < r.waypoints.size(); i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = text((i + 1) + ". " + r.waypoints.get(i).name, 15, Color.WHITE, false);
            row.addView(t, lp(0, -2, 1));
            String[] labels = {"↑", "↓", "✕"};
            for (int k = 0; k < 3; k++) {
                final int action = k;
                row.addView(button(labels[k], new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        List<Route.Waypoint> w = r.waypoints;
                        if (action == 0 && idx > 0) w.add(idx - 1, w.remove(idx));
                        else if (action == 1 && idx < w.size() - 1) w.add(idx + 1, w.remove(idx));
                        else if (action == 2) w.remove(idx);
                        refreshWaypoints(list, r);
                    }
                }), lp(dp(44), dp(40), 0));
            }
            list.addView(row);
        }
    }

    private void placesDialog(final LinearLayout list, final Route r) {
        final List<Route.Waypoint> places = new ArrayList<>(store.library().places.values());
        String[] names = new String[places.size()];
        for (int i = 0; i < names.length; i++) names[i] = places.get(i).name;
        new AlertDialog.Builder(this).setTitle("Místa na Olomoucku a Jesenicku")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        r.waypoints.add(places.get(which));
                        refreshWaypoints(list, r);
                    }
                }).setNegativeButton("Zavřít", null).show();
    }

    private void searchDialog(final LinearLayout list, final Route r) {
        final EditText q = new EditText(this);
        q.setHint("např. Hanušovice, Praděd, Bouzov");
        q.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("Hledat místo").setView(q)
                .setPositiveButton("Hledat", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final String query = q.getText().toString().trim();
                        if (query.isEmpty()) return;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    final List<Route.Waypoint> found = RoutingService.search(query);
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            showResults(found, list, r);
                                        }
                                    });
                                } catch (Exception e) {
                                    toast("Hledání selhalo: " + e.getMessage());
                                }
                            }
                        }).start();
                    }
                }).setNegativeButton("Zrušit", null).show();
    }

    private void showResults(final List<Route.Waypoint> found, final LinearLayout list, final Route r) {
        if (found.isEmpty()) {
            toast("Nic nenalezeno");
            return;
        }
        String[] names = new String[found.size()];
        for (int i = 0; i < names.length; i++) names[i] = found.get(i).name;
        new AlertDialog.Builder(this).setTitle("Vyber místo").setItems(names, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                r.waypoints.add(found.get(which));
                refreshWaypoints(list, r);
            }
        }).setNegativeButton("Zrušit", null).show();
    }

    private Location lastLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {
        }
        return best;
    }
}
