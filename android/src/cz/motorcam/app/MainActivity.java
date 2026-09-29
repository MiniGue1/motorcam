package cz.motorcam.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Hlavní obrazovka MotorCam: náhled kamery s overlayem, kontrolka, pípání,
 * varování před zatáčkami z OpenStreetMap, záznam jízdy (video + CSV senzorů).
 *
 * Smyčka tick() běží 10× za sekundu: poloha -> trasa před motorkou -> zatáčky ->
 * rozhodovací logika (Fusion) -> kontrolka, pípání, overlay. Neuronové sítě běží
 * ve vlastním vlákně na posledním snímku z kamery.
 *
 * Prototyp pro ročníkovou práci – NENÍ to bezpečnostní systém pro reálný provoz.
 */
public class MainActivity extends Activity
        implements LocationListener, SensorEventListener, CameraController.Listener {

    private static final int REQ_PERM = 1, REQ_DET = 2, REQ_SURF = 3;
    private static final long TICK_MS = 100;

    private final Settings settings = new Settings();
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextureView texture;
    private OverlayView overlay;
    private Button btnRec, btnSim;
    private CameraController camera;
    private boolean hardwareStarted, ticking;

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Beeper beeper;
    private MapService maps;
    private final Models models = new Models();
    private Fusion fusion;
    private final Vibration vibration = new Vibration(1.0);

    // strojové učení (vlastní vlákno)
    private HandlerThread mlThread;
    private Handler ml;
    private volatile boolean mlBusy;
    private Bitmap mlFrame;
    private volatile List<Detection> lastDets;
    private volatile float[] lastSurface;
    private volatile float mlFps;
    private long mlLastMs;

    // poloha a pohyb
    private long lastGpsMs;
    private float gpsAccuracy = Float.NaN;
    private double curLat = Double.NaN, curLon = Double.NaN, prevLat = Double.NaN, prevLon = Double.NaN;
    private double speedKmh = Double.NaN, headingRad = Double.NaN;

    // mapa a zatáčky
    private volatile RoadNetwork net;
    private double netLat, netLon;
    private String mapStatus = "Mapa: čekám na polohu";
    private long lastFetchTry = -100000;
    private RoadPath path;
    private double[] radii;
    private Curves.Advice advice;

    // ujetá trasa pro minimapu (kruhový buffer)
    private final float[] trail = new float[2 * 400];
    private int trailStart, trailLen;
    private double trailLat = Double.NaN, trailLon = Double.NaN;

    // simulace jízdy
    private boolean sim;
    private double simLat, simLon, simHeading = Double.NaN;

    // záznam jízdy
    private RideLogger logger;
    private long recStartMs;

    // kalibrace vibrací
    private long calibUntil;
    private final List<Double> calibSamples = new ArrayList<>();

    private int prevLamp;
    private long lastTickMs;

    // =====================================================================================
    //  Životní cyklus
    // =====================================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        super.onCreate(savedInstanceState);
        CrashHandler.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        prefs = getSharedPreferences("motorcam", MODE_PRIVATE);
        settings.load(prefs);
        fusion = new Fusion(settings);
        beeper = new Beeper();
        beeper.muted = prefs.getBoolean("muted", false);
        maps = new MapService(getFilesDir());
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        buildLayout();
        camera = new CameraController(this, texture, this);

        mlThread = new HandlerThread("ml");
        mlThread.start();
        ml = new Handler(mlThread.getLooper());
        ml.post(new Runnable() {
            @Override
            public void run() {
                loadSavedModels();
            }
        });

        String crash = CrashHandler.takeLast(this);
        if (crash != null) {
            showText("Aplikace minule spadla", "Pošli prosím tento text autorovi:\n\n" + crash);
        } else if (!prefs.getBoolean("helpShown", false)) {
            prefs.edit().putBoolean("helpShown", true).apply();
            showHelp();
        }
        if (!hasPermissions()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERM);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        startHardware();
        if (!ticking) {
            ticking = true;
            ui.post(tick);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticking = false;
        ui.removeCallbacks(tick);
        if (logger != null) stopRide();
        stopHardware();
        beeper.setLevel(0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        beeper.release();
        mlThread.quitSafely();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private boolean hasPermission(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasPermissions() {
        return hasPermission(Manifest.permission.CAMERA) && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        stopHardware();
        startHardware();
    }

    private void startHardware() {
        if (hardwareStarted) return;
        hardwareStarted = true;
        if (hasPermission(Manifest.permission.CAMERA)) camera.start();
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            try {
                if (locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER)
                        && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200, 0, this, Looper.getMainLooper());
                }
                if (locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000, 0, this, Looper.getMainLooper());
                }
                // poslední známá poloha, aby se mapa mohla začít stahovat hned
                for (String prov : locationManager.getProviders(true)) {
                    Location l = locationManager.getLastKnownLocation(prov);
                    if (l != null && Double.isNaN(curLat)) {
                        curLat = l.getLatitude();
                        curLon = l.getLongitude();
                    }
                }
            } catch (SecurityException | IllegalArgumentException e) {
                toast("Poloha: " + e.getMessage());
            }
        }
        Sensor acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyr = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (acc != null) sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
        if (gyr != null) sensorManager.registerListener(this, gyr, SensorManager.SENSOR_DELAY_GAME);
    }

    private void stopHardware() {
        if (!hardwareStarted) return;
        hardwareStarted = false;
        camera.stop();
        try {
            locationManager.removeUpdates(this);
        } catch (Exception ignored) {
        }
        sensorManager.unregisterListener(this);
    }

    // =====================================================================================
    //  Uživatelské rozhraní
    // =====================================================================================

    private void buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        texture = new TextureView(this);
        overlay = new OverlayView(this);
        root.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        btnRec = makeButton("● REC");
        btnSim = makeButton("SIM");
        Button btnMenu = makeButton("☰");
        col.addView(btnRec);
        col.addView(btnSim);
        col.addView(btnMenu);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL);
        lp.leftMargin = dp(10);
        root.addView(col, lp);
        setContentView(root);

        btnRec.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (logger == null) startRide();
                else stopRide();
            }
        });
        btnSim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSim();
            }
        });
        btnMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
    }

    private Button makeButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(160, 0, 0, 0));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.argb(120, 255, 255, 255));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(76), dp(48));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String lastToast = "";
    private long lastToastMs;

    private void toast(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.elapsedRealtime();
                if (s.equals(lastToast) && now - lastToastMs < 5000) return;   // stejnou zprávu neopakovat
                lastToast = s;
                lastToastMs = now;
                Toast.makeText(MainActivity.this, s, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showText(String title, String body) {
        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle(title).setView(sv).setPositiveButton("OK", null).show();
    }

    private void showHelp() {
        showText("MotorCam – nápověda",
                "Prototyp ročníkové práce. NENÍ to bezpečnostní systém – vždy se řiď svým úsudkem.\n\n"
                        + "Kontrolka: ZELENÁ = OK, ORANŽOVÁ = změna povrchu / štěrk / zatáčka, "
                        + "ČERVENÁ = díra před motorkou, štěrk ve vyšší rychlosti, výrazně rychle do zatáčky.\n\n"
                        + "Zatáčky: aplikace stáhne silnice z OpenStreetMap (potřebuje internet a GPS), najde zatáčky "
                        + "300 m před tebou a spočítá doporučenou rychlost v = √(g·R·tanθ). Pípání: jedno = zpomal mírně, "
                        + "rychlé = zpomal hodně.\n\n"
                        + "Povrch: bez modelu se odhaduje z vibrací (mobil pevně na řídítkách). Menu ☰ → Kalibrovat "
                        + "vibrace – 10 s jízdy po hladkém asfaltu.\n\n"
                        + "Díry a povrch z kamery: natrénuj modely v Colabu (notebooky 02 a 03 v repozitáři), exportuj "
                        + ".tflite a načti přes menu ☰.\n\n"
                        + "SIM: simulovaná jízda po okolních silnicích (test zatáček doma).\n"
                        + "REC: nahraje video + CSV senzorů do složky Stažené/MotorCam – data pro trénink.");
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Načíst model děr (.tflite)" + (models.hasDetector() ? " ✓" : ""));
        m.add(0, 2, 0, "Načíst model povrchu (.tflite)" + (models.hasSurface() ? " ✓" : ""));
        m.add(0, 3, 0, "Odebrat modely");
        m.add(0, 4, 0, "Kalibrovat vibrace (10 s hladký asfalt)");
        m.add(0, 5, 0, "Nastavení");
        m.add(0, 6, 0, "Znovu stáhnout mapu");
        m.add(0, 7, 0, beeper.muted ? "Zapnout zvuk" : "Vypnout zvuk");
        m.add(0, 8, 0, "Nápověda");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1: pickModel(REQ_DET); break;
                    case 2: pickModel(REQ_SURF); break;
                    case 3: removeModels(); break;
                    case 4: startCalibration(); break;
                    case 5: showSettings(); break;
                    case 6: reloadMap(); break;
                    case 7:
                        beeper.muted = !beeper.muted;
                        prefs.edit().putBoolean("muted", beeper.muted).apply();
                        break;
                    case 8: showHelp(); break;
                    default: return false;
                }
                return true;
            }
        });
        pm.show();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        final EditText[] edits = new EditText[Settings.KEYS.length];
        for (int i = 0; i < Settings.KEYS.length; i++) {
            TextView label = new TextView(this);
            label.setText(Settings.LABELS[i]);
            box.addView(label);
            EditText e = new EditText(this);
            e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            e.setText(String.format(Locale.US, "%s", trimNum(settings.get(Settings.KEYS[i]))));
            box.addView(e);
            edits[i] = e;
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Nastavení").setView(sv)
                .setPositiveButton("Uložit", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        for (int i = 0; i < edits.length; i++) {
                            try {
                                settings.set(Settings.KEYS[i], Double.parseDouble(edits[i].getText().toString().replace(',', '.')));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        settings.save(prefs);
                    }
                })
                .setNegativeButton("Zrušit", null).show();
    }

    private static String trimNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // =====================================================================================
    //  Modely
    // =====================================================================================

    private void pickModel(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            toast("Nelze otevřít výběr souboru: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final boolean detector = requestCode == REQ_DET;
        ml.post(new Runnable() {
            @Override
            public void run() {
                File dst = new File(getFilesDir(), detector ? "detector.tflite" : "surface.tflite");
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    FileOutputStream out = new FileOutputStream(dst);
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    out.close();
                    String info = detector ? models.loadDetector(dst) : models.loadSurface(dst);
                    toast((detector ? "Model děr načten: " : "Model povrchu načten: ") + info);
                } catch (Throwable e) {
                    dst.delete();
                    toast("Model se nepodařilo načíst: " + e.getMessage());
                }
            }
        });
    }

    private void loadSavedModels() {
        File d = new File(getFilesDir(), "detector.tflite"), s = new File(getFilesDir(), "surface.tflite");
        try {
            if (d.exists()) models.loadDetector(d);
        } catch (Throwable e) {
            toast("Uložený model děr nejde načíst: " + e.getMessage());
        }
        try {
            if (s.exists()) models.loadSurface(s);
        } catch (Throwable e) {
            toast("Uložený model povrchu nejde načíst: " + e.getMessage());
        }
    }

    private void removeModels() {
        ml.post(new Runnable() {
            @Override
            public void run() {
                models.closeDetector();
                models.closeSurface();
                new File(getFilesDir(), "detector.tflite").delete();
                new File(getFilesDir(), "surface.tflite").delete();
                lastDets = null;
                lastSurface = null;
                toast("Modely odebrány");
            }
        });
    }

    /** Pošle aktuální snímek z kamery neuronovým sítím (když na tom nepracují). */
    private void requestInference() {
        if (mlBusy || !(models.hasDetector() || models.hasSurface()) || !texture.isAvailable()) return;
        int vw = texture.getWidth(), vh = texture.getHeight();
        if (vw == 0 || vh == 0) return;
        int fh = Math.max(1, Math.round(640f * vh / vw));
        if (mlFrame == null || mlFrame.getHeight() != fh) mlFrame = Bitmap.createBitmap(640, fh, Bitmap.Config.ARGB_8888);
        final Bitmap frame = texture.getBitmap(mlFrame);
        if (frame == null) return;
        mlBusy = true;
        ml.post(new Runnable() {
            @Override
            public void run() {
                try {
                    long t0 = SystemClock.elapsedRealtime();
                    lastDets = models.detect(frame, (float) settings.detConf);
                    lastSurface = models.classify(frame);
                    long t1 = SystemClock.elapsedRealtime();
                    float fps = 1000f / Math.max(1, t1 - (mlLastMs == 0 ? t0 : mlLastMs));
                    mlFps = mlFps == 0 ? fps : 0.8f * mlFps + 0.2f * fps;
                    mlLastMs = t1;
                } catch (Throwable e) {
                    toast("Chyba modelu: " + e.getMessage());
                    models.closeDetector();
                    models.closeSurface();
                } finally {
                    mlBusy = false;
                }
            }
        });
    }

    // =====================================================================================
    //  Senzory a poloha
    // =====================================================================================

    @Override
    public void onLocationChanged(Location loc) {
        boolean gps = LocationManager.GPS_PROVIDER.equals(loc.getProvider());
        long now = SystemClock.elapsedRealtime();
        if (gps) {
            lastGpsMs = now;
            gpsAccuracy = loc.hasAccuracy() ? loc.getAccuracy() : Float.NaN;
            if (logger != null) {
                logger.gps(loc.getElapsedRealtimeNanos(), loc.getLatitude(), loc.getLongitude(),
                        loc.hasSpeed() ? loc.getSpeed() * 3.6 : Double.NaN, loc.hasBearing() ? loc.getBearing() : Double.NaN,
                        loc.hasAccuracy() ? loc.getAccuracy() : Double.NaN);
            }
        } else if (now - lastGpsMs < 10000) {
            return;   // síťová poloha jen když GPS nic nehlásí
        }
        if (sim) return;
        prevLat = curLat;
        prevLon = curLon;
        curLat = loc.getLatitude();
        curLon = loc.getLongitude();
        if (!gps) {
            speedKmh = Double.NaN;
            return;
        }
        speedKmh = loc.hasSpeed() ? loc.getSpeed() * 3.6 : speedKmh;
        if (loc.hasBearing() && loc.hasSpeed() && loc.getSpeed() > 1.5) {
            headingRad = Geo.bearingToAngle(loc.getBearing());
        } else if (!Double.isNaN(prevLat) && Geo.distance(prevLat, prevLon, curLat, curLon) > 8) {
            Geo g = new Geo(prevLat, prevLon);
            headingRad = Math.atan2(g.y(curLat), g.x(curLon));
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            vibration.add(e.timestamp / 1e9, e.values[0], e.values[1], e.values[2]);
            if (logger != null) logger.acc(e.timestamp, e.values[0], e.values[1], e.values[2]);
        } else if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE && logger != null) {
            logger.gyr(e.timestamp, e.values[0], e.values[1], e.values[2]);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // =====================================================================================
    //  Mapa
    // =====================================================================================

    private void ensureMap(long now) {
        if (Double.isNaN(curLat) || maps.isBusy()) return;
        boolean need = net == null || Geo.distance(curLat, curLon, netLat, netLon) > MapService.RADIUS_M * 0.6;
        if (!need || now - lastFetchTry < 15000) return;
        lastFetchTry = now;
        mapStatus = "Mapa: stahuji silnice z OSM…";
        maps.fetch(curLat, curLon, new MapService.Callback() {
            @Override
            public void onLoaded(final RoadNetwork n, final double lat, final double lon, final boolean fromCache) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        net = n;
                        netLat = lat;
                        netLon = lon;
                        mapStatus = "Mapa: " + n.ways.size() + " silnic" + (fromCache ? " (uloženo)" : "");
                    }
                });
            }

            @Override
            public void onError(final String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        mapStatus = "Mapa: chyba (" + message + "), zkusím znovu";
                    }
                });
            }
        });
    }

    private void reloadMap() {
        new File(getFilesDir(), "osm_cache.json").delete();
        new File(getFilesDir(), "osm_cache.txt").delete();
        net = null;
        lastFetchTry = -100000;
        mapStatus = "Mapa: stahuji znovu…";
    }

    // =====================================================================================
    //  Simulace
    // =====================================================================================

    private void toggleSim() {
        if (sim) {
            sim = false;
            speedKmh = Double.NaN;
            btnSim.setText("SIM");
            return;
        }
        if (Double.isNaN(curLat)) {
            toast("Simulace potřebuje aspoň přibližnou polohu – zapni polohu a počkej chvilku.");
            return;
        }
        sim = true;
        simLat = curLat;
        simLon = curLon;
        simHeading = headingRad;
        btnSim.setText("■ SIM");
        toast(String.format(Locale.US, "Simulace jízdy %.0f km/h po nejbližší silnici", settings.simSpeedKmh));
    }

    /** Posune simulovanou motorku po silnici o v·dt. */
    private void stepSim(double dt) {
        RoadNetwork n = net;
        if (n == null) return;
        double x = n.geo.x(simLon), y = n.geo.y(simLat);
        RoadPath.Match m = RoadPath.match(n, x, y, simHeading, 80);
        if (m == null && !Double.isNaN(simHeading)) m = RoadPath.match(n, x, y, Double.NaN, 80);
        if (m == null) {
            sim = false;
            btnSim.setText("SIM");
            toast("Simulace: v okolí 80 m není žádná silnice");
            return;
        }
        double v = settings.simSpeedKmh / 3.6, dist = v * dt;
        RoadPath p = RoadPath.build(n, m, Math.max(30, dist * 3), 0, 1.0);
        int i = p.zeroIdx;
        while (i + 1 < p.s.length && p.s[i] < dist) i++;
        if (p.s[p.s.length - 1] < dist) {
            simHeading = Double.isNaN(simHeading) ? 0 : simHeading + Math.PI;   // slepá ulice -> otočit
            return;
        }
        int a = Math.max(0, i - 2), b = Math.min(p.s.length - 1, i + 2);
        simHeading = Math.atan2(p.y[b] - p.y[a], p.x[b] - p.x[a]);
        simLat = n.geo.lat(p.y[i]);
        simLon = n.geo.lon(p.x[i]);
        curLat = simLat;
        curLon = simLon;
        speedKmh = settings.simSpeedKmh;
        headingRad = simHeading;
    }

    // =====================================================================================
    //  Záznam jízdy
    // =====================================================================================

    private void startRide() {
        File dir = getExternalFilesDir("jizdy");
        if (dir == null) dir = new File(getFilesDir(), "jizdy");
        dir.mkdirs();
        String name = "jizda_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        try {
            logger = new RideLogger(new File(dir, name + ".csv"), SystemClock.elapsedRealtimeNanos());
        } catch (Exception e) {
            toast("Nelze vytvořit záznam: " + e.getMessage());
            return;
        }
        recStartMs = SystemClock.elapsedRealtime();
        if (hasPermission(Manifest.permission.CAMERA)) camera.startRecording(new File(dir, name + ".mp4"));
        btnRec.setText("■ STOP");
    }

    private void stopRide() {
        final RideLogger l = logger;
        logger = null;
        btnRec.setText("● REC");
        if (camera.recording) camera.stopRecording();
        l.close();
        exportLater(l.file, "text/csv", 0);
    }

    /** Zkopíruje soubor do Stažené/MotorCam (Android 10+), aby šel snadno najít a poslat. */
    private void exportLater(final File f, final String mime, long delayMs) {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String where = exportToDownloads(f, mime);
                        toast("Uloženo: " + where);
                    }
                }, "export");
                t.start();
            }
        }, delayMs);
    }

    private String exportToDownloads(File f, String mime) {
        if (!f.exists()) return "(soubor chybí)";
        if (Build.VERSION.SDK_INT < 29) return f.getAbsolutePath();
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MotorCam");
            ContentResolver cr = getContentResolver();
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return f.getAbsolutePath();
            OutputStream out = cr.openOutputStream(uri);
            InputStream in = new FileInputStream(f);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            f.delete();
            return "Stažené/MotorCam/" + f.getName();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }

    @Override
    public void onCameraError(String message) {
        toast(message);
    }

    @Override
    public void onRecordingStarted(final long ns) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (logger != null) logger.event(ns, "video_start");
            }
        });
    }

    @Override
    public void onRecordingStopped(File file) {
        if (file != null) exportLater(file, "video/mp4", 0);
    }

    // =====================================================================================
    //  Kalibrace vibrací
    // =====================================================================================

    private void startCalibration() {
        calibSamples.clear();
        calibUntil = SystemClock.elapsedRealtime() + 10000;
        toast("Kalibrace 10 s: jeď rovnoměrně (> 15 km/h) po hladkém asfaltu");
    }

    private void updateCalibration(long now) {
        if (calibUntil == 0) return;
        if (now < calibUntil) {
            // jen za jízdy – ve stoje jsou vibrace jen od motoru a základ by vyšel moc nízko
            if (now % 500 < TICK_MS && !Double.isNaN(speedKmh) && speedKmh > 15) calibSamples.add(vibration.rms);
            return;
        }
        calibUntil = 0;
        if (calibSamples.size() < 5) {
            toast("Kalibrace se nepovedla – jeď aspoň 15 km/h po celých 10 s");
            return;
        }
        Double[] arr = calibSamples.toArray(new Double[0]);
        Arrays.sort(arr);
        settings.vibBaseline = Math.max(0.05, arr[arr.length / 2]);
        settings.save(prefs);
        toast(String.format(Locale.US, "Kalibrace hotová: hladký asfalt = %.2f m/s²", settings.vibBaseline));
    }

    // =====================================================================================
    //  Hlavní smyčka
    // =====================================================================================

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            try {
                step();
            } catch (Throwable e) {
                toast("Chyba: " + e);   // chyba v jednom kroku nesmí shodit celou aplikaci
            }
            ui.postDelayed(this, TICK_MS);
        }
    };

    private void step() {
        long now = SystemClock.elapsedRealtime();
        double dt = lastTickMs == 0 ? TICK_MS / 1000.0 : Math.min(0.5, (now - lastTickMs) / 1000.0);
        lastTickMs = now;
        if (sim) stepSim(dt);
        ensureMap(now);
        updateCalibration(now);
        boolean gpsOk = sim || now - lastGpsMs < 3000;
        if (!gpsOk && !sim) speedKmh = Double.NaN;

        // --- trasa a zatáčky ---
        RoadNetwork n = net;
        advice = null;
        path = null;
        radii = null;
        double px = 0, py = 0;
        String curveStatus = mapStatus;
        if (n != null && !Double.isNaN(curLat)) {
            px = n.geo.x(curLon);
            py = n.geo.y(curLat);
            if (!gpsOk) {
                curveStatus = "Čekám na GPS…";
            } else if (Double.isNaN(headingRad)) {
                curveStatus = "Čekám na směr jízdy…";
            } else {
                RoadPath.Match m = RoadPath.match(n, px, py, headingRad, 40);
                if (m == null) {
                    curveStatus = "Mimo silnici v mapě";
                } else {
                    path = RoadPath.build(n, m, settings.lookaheadM + 40, 40, 5);
                    radii = Curves.radii(path, 3, 4);
                    List<Curves.Curve> curves = Curves.find(path, radii, settings.radiusThresholdM, n);
                    List<Curves.Curve> inRange = new ArrayList<>();
                    for (Curves.Curve c : curves) if (c.startS <= settings.lookaheadM) inRange.add(c);
                    double v = Double.isNaN(speedKmh) ? 0 : speedKmh / 3.6;
                    advice = Curves.advise(inRange, v, fusion.lowGrip, settings);
                }
            }
            addTrail(n, px, py);
        }

        // --- rozhodovací logika ---
        Fusion.Inputs in = new Fusion.Inputs();
        in.surfaceProbs = models.hasSurface() ? lastSurface : null;
        in.detections = models.hasDetector() ? lastDets : null;
        in.potholeClass = models.potholeClass;
        in.speedKmh = speedKmh;
        in.advice = advice;
        in.vibLevel = vibration.level(speedKmh, settings.vibBaseline);
        fusion.update(now, in);

        int beep = advice != null && !Double.isNaN(speedKmh) && speedKmh > 10 ? advice.level : 0;
        beeper.setLevel(beep);
        if (fusion.level == Fusion.RED && prevLamp != Fusion.RED && fusion.reason.startsWith("DÍRA")) beeper.alert();
        prevLamp = fusion.level;

        String surfaceText;
        String surfaceSource;
        if (in.surfaceProbs != null && fusion.surface >= 0) {
            surfaceText = String.format(Locale.US, "%s %d %%", Fusion.SURFACE_CZ[fusion.surface], Math.round(fusion.surfaceProb * 100));
            surfaceSource = "kamera";
        } else if (in.vibLevel >= 0) {
            surfaceText = Fusion.VIB_CZ[in.vibLevel];
            surfaceSource = String.format(Locale.US, "vibrace %.1f m/s² (hladký %.1f)", vibration.rms, settings.vibBaseline);
        } else {
            surfaceText = "povrch: —";
            surfaceSource = models.hasSurface() ? "kamera: nejistý" : "vibrace: stojíš / čekám";
        }
        if (logger != null) logger.state(SystemClock.elapsedRealtimeNanos(), fusion.level, surfaceText, fusion.reason);

        // --- overlay ---
        OverlayView.State s = new OverlayView.State();
        s.detections = in.detections;
        s.detNames = models.detNames;
        s.pothole = models.potholeClass;
        s.surfaceText = surfaceText;
        s.surfaceSource = surfaceSource;
        s.lamp = fusion.level;
        s.reason = fusion.reason;
        s.speedKmh = speedKmh;
        s.advice = advice;
        s.mapStatus = curveStatus;
        s.net = n;
        s.path = path;
        s.radii = radii;
        s.posX = px;
        s.posY = py;
        s.heading = headingRad;
        s.trail = trail;
        s.trailStart = trailStart;
        s.trailLen = trailLen;
        s.settings = settings;
        s.lowGrip = fusion.lowGrip;
        StringBuilder st = new StringBuilder();
        st.append(sim ? "SIMULACE" : gpsOk ? String.format(Locale.US, "GPS ±%.0f m", gpsAccuracy) : "GPS: hledám");
        st.append(" · ").append(mapStatus);
        st.append(" · díry: ").append(models.hasDetector() ? "model" : "—");
        st.append(" · povrch: ").append(models.hasSurface() ? "model" : "vibrace");
        if (models.hasDetector() || models.hasSurface()) st.append(String.format(Locale.US, " · %.1f FPS", mlFps));
        if (logger != null) {
            long sec = (now - recStartMs) / 1000;
            st.append(String.format(Locale.US, " · ● REC %02d:%02d", sec / 60, sec % 60));
        }
        if (calibUntil > 0) st.append(" · KALIBRACE");
        s.status = st.toString();
        if (!hasPermissions()) s.banner = "Povol kameru a polohu (Nastavení → Aplikace → MotorCam)";
        overlay.setState(s);

        requestInference();
    }

    private void addTrail(RoadNetwork n, double px, double py) {
        if (!Double.isNaN(trailLat) && Geo.distance(trailLat, trailLon, curLat, curLon) < 5) return;
        trailLat = curLat;
        trailLon = curLon;
        int cap = trail.length / 2;
        int idx = (trailStart + trailLen) % cap;
        trail[2 * idx] = (float) px;
        trail[2 * idx + 1] = (float) py;
        if (trailLen < cap) trailLen++;
        else trailStart = (trailStart + 1) % cap;
        if (n != null && trailLen > 1 && n != lastTrailNet) {
            // nová mapa = nový počátek souřadnic -> starou stopu zahodíme
            trailStart = idx;
            trailLen = 1;
        }
        lastTrailNet = n;
    }

    private RoadNetwork lastTrailNet;
}
