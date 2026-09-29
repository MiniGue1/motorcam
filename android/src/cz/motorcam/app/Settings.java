package cz.motorcam.app;

import android.content.SharedPreferences;

/**
 * Nastavitelné parametry (stejné významy jako v configs/config.yaml).
 * Hodnoty se ukládají do SharedPreferences, výchozí hodnoty jsou zde.
 */
public final class Settings {
    // --- zatáčky ---
    public double leanDeg = 25;          // bezpečný náklon θ [°]
    public double leanDegLowGrip = 15;   // θ na štěrku / mokru [°]
    public double reactionS = 1.0;       // reakční doba [s]
    public double brakeMps2 = 4.0;       // zpomalení při brzdění [m/s²]
    public double marginM = 20;          // rezerva [m]
    public double lookaheadM = 300;      // jak daleko dopředu hledat zatáčky [m]
    public double radiusThresholdM = 150; // R pod touto hodnotou = zatáčka [m]
    // --- kontrolka ---
    public double highSpeedKmh = 50;     // štěrk nad touto rychlostí = ČERVENÁ
    public double windowS = 1.0;         // klouzavé okno [s]
    public double holdS = 2.0;           // jak dlouho držet vyšší stupeň [s]
    // --- modely ---
    public double detConf = 0.40;        // min. jistota detekce
    public double surfaceConf = 0.55;    // min. jistota povrchu
    // --- vibrace ---
    public double vibBaseline = 0.8;     // RMS zrychlení na hladkém asfaltu [m/s²]
    // --- simulace ---
    public double simSpeedKmh = 80;

    public static final String[] KEYS = {
        "leanDeg", "leanDegLowGrip", "reactionS", "brakeMps2", "marginM", "lookaheadM",
        "radiusThresholdM", "highSpeedKmh", "detConf", "surfaceConf", "simSpeedKmh", "vibBaseline"};
    public static final String[] LABELS = {
        "Náklon θ [°]", "Náklon na štěrku/mokru [°]", "Reakční doba [s]", "Brzdné zpomalení [m/s²]",
        "Rezerva [m]", "Dohled dopředu [m]", "Práh poloměru zatáčky [m]", "Rychlost pro štěrk = červená [km/h]",
        "Min. jistota detekce děr", "Min. jistota povrchu", "Rychlost simulace [km/h]", "Vibrace – hladký asfalt [m/s²]"};

    public double get(String key) {
        switch (key) {
            case "leanDeg": return leanDeg;
            case "leanDegLowGrip": return leanDegLowGrip;
            case "reactionS": return reactionS;
            case "brakeMps2": return brakeMps2;
            case "marginM": return marginM;
            case "lookaheadM": return lookaheadM;
            case "radiusThresholdM": return radiusThresholdM;
            case "highSpeedKmh": return highSpeedKmh;
            case "detConf": return detConf;
            case "surfaceConf": return surfaceConf;
            case "simSpeedKmh": return simSpeedKmh;
            case "vibBaseline": return vibBaseline;
            default: throw new IllegalArgumentException(key);
        }
    }

    public void set(String key, double v) {
        switch (key) {
            case "leanDeg": leanDeg = clamp(v, 5, 50); break;
            case "leanDegLowGrip": leanDegLowGrip = clamp(v, 5, 50); break;
            case "reactionS": reactionS = clamp(v, 0, 5); break;
            case "brakeMps2": brakeMps2 = clamp(v, 1, 10); break;
            case "marginM": marginM = clamp(v, 0, 200); break;
            case "lookaheadM": lookaheadM = clamp(v, 100, 1000); break;
            case "radiusThresholdM": radiusThresholdM = clamp(v, 20, 500); break;
            case "highSpeedKmh": highSpeedKmh = clamp(v, 10, 200); break;
            case "detConf": detConf = clamp(v, 0.05, 0.99); break;
            case "surfaceConf": surfaceConf = clamp(v, 0.2, 0.99); break;
            case "simSpeedKmh": simSpeedKmh = clamp(v, 5, 200); break;
            case "vibBaseline": vibBaseline = clamp(v, 0.05, 20); break;
            default: throw new IllegalArgumentException(key);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public void load(SharedPreferences p) {
        for (String k : KEYS) {
            if (p.contains(k)) set(k, Double.longBitsToDouble(p.getLong(k, 0)));
        }
    }

    public void save(SharedPreferences p) {
        SharedPreferences.Editor e = p.edit();
        for (String k : KEYS) e.putLong(k, Double.doubleToLongBits(get(k)));
        e.apply();
    }
}
