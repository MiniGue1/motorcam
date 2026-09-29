package cz.motorcam.app;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Rozhodovací logika kontrolky: spojí povrch (kamera nebo vibrace), detekce děr,
 * rychlost z GPS a varování před zatáčkou do jedné barvy.
 *
 * Proti blikání:
 *  - stupeň se rozsvítí, až když byl „syrově“ naměřen aspoň ve 30 % vzorků klouzavého okna,
 *  - pak se drží ještě holdS sekund (rychle nahoru, pomalu dolů = hystereze).
 */
public final class Fusion {
    public static final int GREEN = 0, ORANGE = 1, RED = 2;
    public static final String[] SURFACE_CZ = {"asfalt", "rozbitý asfalt", "štěrk", "hlína / bláto", "mokro"};
    public static final int ASFALT = 0, ROZBITY = 1, STERK = 2, HLINA = 3, MOKRO = 4;
    public static final String[] VIB_CZ = {"hladký povrch", "nerovný povrch", "velmi nerovný povrch"};

    /** Vstupy jednoho kroku. */
    public static final class Inputs {
        public float[] surfaceProbs;          // pravděpodobnosti 5 tříd z kamery, null = model není
        public int vibLevel = -1;             // 0/1/2 z akcelerometru, -1 = neznámo
        public List<Detection> detections;    // null = model děr není
        public boolean[] potholeClass;        // které třídy detektoru jsou „díra“
        public double speedKmh = Double.NaN;
        public Curves.Advice advice;          // null = bez mapy
    }

    private final Settings settings;
    private final ArrayDeque<long[]> history = new ArrayDeque<>();   // {čas ms, syrový stupeň}
    private final long[] lastConfirmed = {0, Long.MIN_VALUE / 2, Long.MIN_VALUE / 2};
    private final String[] reasonOf = {"", "", ""};

    private float[] smoothProbs;
    private long lastUpdateMs;
    private int stableSurface = -1, candidateSurface = -1;
    private long candidateSinceMs, surfaceChangeMs = Long.MIN_VALUE / 2;

    // výstupy
    public int level = GREEN;
    public String reason = "";
    public int surface = -1;          // vyhlazená třída povrchu (kamera)
    public float surfaceProb;
    public boolean lowGrip;           // štěrk / hlína / mokro -> menší náklon v zatáčkách

    public Fusion(Settings settings) {
        this.settings = settings;
    }

    public void update(long nowMs, Inputs in) {
        updateSurface(nowMs, in.surfaceProbs);
        double speed = Double.isNaN(in.speedKmh) ? 0 : in.speedKmh;
        boolean fast = speed > settings.highSpeedKmh;
        boolean loose = surface == STERK || surface == HLINA;
        lowGrip = loose || surface == MOKRO || in.vibLevel == 2;

        int raw = GREEN;
        String why = "";
        // --- ČERVENÁ ---
        boolean holeAhead = false, holeAny = false, crackAhead = false;
        if (in.detections != null) {
            for (Detection d : in.detections) {
                boolean hole = in.potholeClass != null && d.cls < in.potholeClass.length && in.potholeClass[d.cls];
                if (hole && d.inCorridor()) holeAhead = true;
                else if (hole) holeAny = true;
                else if (d.inCorridor()) crackAhead = true;
            }
        }
        if (holeAhead) {
            raw = RED;
            why = "DÍRA PŘED MOTORKOU";
        } else if (in.advice != null && in.advice.level == 2) {
            raw = RED;
            why = String.format(Locale.US, "ZPOMAL! zatáčka R=%.0f m → %.0f km/h", in.advice.curve.minR, in.advice.vMaxKmh);
        } else if (loose && fast) {
            raw = RED;
            why = String.format(Locale.US, "%s v %.0f km/h", SURFACE_CZ[surface], speed);
        } else if (in.vibLevel == 2 && fast) {
            raw = RED;
            why = String.format(Locale.US, "velmi nerovný povrch v %.0f km/h", speed);
        }
        // --- ORANŽOVÁ ---
        else if (in.advice != null && in.advice.level == 1) {
            raw = ORANGE;
            why = String.format(Locale.US, "zatáčka R=%.0f m → %.0f km/h", in.advice.curve.minR, in.advice.vMaxKmh);
        } else if (nowMs - surfaceChangeMs < 3000) {
            raw = ORANGE;
            why = "změna povrchu: " + SURFACE_CZ[surface];
        } else if (surface > ASFALT) {
            raw = ORANGE;
            why = SURFACE_CZ[surface];
        } else if (holeAny || crackAhead) {
            raw = ORANGE;
            why = holeAny ? "díra v záběru" : "trhlina před motorkou";
        } else if (in.vibLevel == 1) {
            raw = ORANGE;
            why = "nerovný povrch";
        }
        reasonOf[raw] = why;

        // --- vyhlazení v čase ---
        history.addLast(new long[]{nowMs, raw});
        long windowMs = (long) (settings.windowS * 1000);
        while (!history.isEmpty() && history.peekFirst()[0] < nowMs - windowMs) history.removeFirst();
        for (int l = RED; l >= ORANGE; l--) {
            int count = 0;
            Iterator<long[]> it = history.iterator();
            while (it.hasNext()) if (it.next()[1] >= l) count++;
            if (count >= Math.max(1, Math.round(0.3 * history.size()))) lastConfirmed[l] = nowMs;
        }
        long holdMs = (long) (settings.holdS * 1000);
        level = GREEN;
        for (int l = RED; l >= ORANGE; l--) {
            if (nowMs - lastConfirmed[l] <= holdMs) {
                level = l;
                break;
            }
        }
        reason = level == GREEN ? "OK" : reasonOf[level];
        lastUpdateMs = nowMs;
    }

    /** Exponenciální vyhlazení pravděpodobností povrchu + detekce změny (stabilní ≥ 1 s). */
    private void updateSurface(long nowMs, float[] probs) {
        if (probs == null) {
            surface = -1;
            smoothProbs = null;
            return;
        }
        double dt = lastUpdateMs == 0 ? 0.1 : Math.min(1, (nowMs - lastUpdateMs) / 1000.0);
        float alpha = (float) Math.min(1, dt / Math.max(0.05, settings.windowS));
        if (smoothProbs == null || smoothProbs.length != probs.length) smoothProbs = probs.clone();
        for (int i = 0; i < probs.length; i++) smoothProbs[i] += alpha * (probs[i] - smoothProbs[i]);
        int best = 0;
        for (int i = 1; i < smoothProbs.length; i++) if (smoothProbs[i] > smoothProbs[best]) best = i;
        surfaceProb = smoothProbs[best];
        int now = surfaceProb >= settings.surfaceConf ? best : stableSurface;
        if (now != candidateSurface) {
            candidateSurface = now;
            candidateSinceMs = nowMs;
        }
        if (candidateSurface != stableSurface && nowMs - candidateSinceMs >= 1000) {
            if (stableSurface >= 0 && candidateSurface >= 0) surfaceChangeMs = nowMs;
            stableSurface = candidateSurface;
        }
        surface = stableSurface;
    }
}
