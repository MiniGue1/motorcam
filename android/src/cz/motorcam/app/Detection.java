package cz.motorcam.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Jeden detekovaný objekt. Souřadnice jsou normalizované 0–1 vůči celému snímku kamery. */
public final class Detection {
    public final float x1, y1, x2, y2, conf;
    public final int cls;

    public Detection(float x1, float y1, float x2, float y2, int cls, float conf) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        this.cls = cls;
        this.conf = conf;
    }

    public float area() {
        return Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
    }

    static float iou(Detection a, Detection b) {
        float ix = Math.max(0, Math.min(a.x2, b.x2) - Math.max(a.x1, b.x1));
        float iy = Math.max(0, Math.min(a.y2, b.y2) - Math.max(a.y1, b.y1));
        float inter = ix * iy;
        float union = a.area() + b.area() - inter;
        return union > 0 ? inter / union : 0;
    }

    /**
     * Objekt je v „koridoru“ před motorkou: střed vodorovně v prostřední části obrazu
     * a spodní hrana v dolní části (blízko). Odpovídá tomu, kam motorka za chvíli vjede.
     */
    public boolean inCorridor() {
        float cx = (x1 + x2) / 2;
        return cx > 0.28f && cx < 0.72f && y2 > 0.55f;
    }

    /**
     * Dekódování výstupu YOLO (ultralytics, export do TFLite).
     * Výstup má tvar [4+nc][N] nebo [N][4+nc]; první 4 hodnoty = střed x, y, šířka, výška
     * (normalizované 0–1 nebo v pixelech vstupu), pak jistoty tříd.
     * Souřadnice se převedou zpět z „letterbox“ vstupu (obraz vložený do čtverce s okraji)
     * na souřadnice původního snímku.
     *
     * @param out     výstup modelu bez dávkové dimenze
     * @param inSize  velikost čtvercového vstupu modelu [px]
     * @param padX    okraj letterboxu vlevo [px vstupu]
     * @param padY    okraj letterboxu nahoře [px vstupu]
     * @param scale   měřítko (px vstupu na 1 px snímku)
     * @param frameW  šířka snímku [px]
     * @param frameH  výška snímku [px]
     */
    public static List<Detection> decodeYolo(float[][] out, int inSize, float padX, float padY, float scale,
                                             int frameW, int frameH, float confTh, float iouTh) {
        boolean channelsFirst = out.length < out[0].length;   // [4+nc][N]
        int n = channelsFirst ? out[0].length : out.length;
        int c = channelsFirst ? out.length : out[0].length;
        int nc = c - 4;
        // souřadnice normalizované? (max. hodnota středu ≤ ~1.5)
        float maxCoord = 0;
        for (int i = 0; i < n; i++) maxCoord = Math.max(maxCoord, channelsFirst ? out[0][i] : out[i][0]);
        float coordScale = maxCoord <= 1.5f ? inSize : 1;

        List<Detection> cand = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int best = -1;
            float bestP = confTh;
            for (int k = 0; k < nc; k++) {
                float p = channelsFirst ? out[4 + k][i] : out[i][4 + k];
                if (p > bestP) {
                    bestP = p;
                    best = k;
                }
            }
            if (best < 0) continue;
            float cx = (channelsFirst ? out[0][i] : out[i][0]) * coordScale;
            float cy = (channelsFirst ? out[1][i] : out[i][1]) * coordScale;
            float w = (channelsFirst ? out[2][i] : out[i][2]) * coordScale;
            float h = (channelsFirst ? out[3][i] : out[i][3]) * coordScale;
            // z letterboxu na pixely snímku a pak na 0–1
            float x1 = ((cx - w / 2) - padX) / scale / frameW, x2 = ((cx + w / 2) - padX) / scale / frameW;
            float y1 = ((cy - h / 2) - padY) / scale / frameH, y2 = ((cy + h / 2) - padY) / scale / frameH;
            cand.add(new Detection(clamp(x1), clamp(y1), clamp(x2), clamp(y2), best, bestP));
        }
        return nms(cand, iouTh);
    }

    /** Non-maximum suppression po třídách. */
    public static List<Detection> nms(List<Detection> dets, float iouTh) {
        Collections.sort(dets, new Comparator<Detection>() {
            @Override
            public int compare(Detection a, Detection b) {
                return Float.compare(b.conf, a.conf);
            }
        });
        List<Detection> keep = new ArrayList<>();
        for (Detection d : dets) {
            boolean ok = true;
            for (Detection k : keep) {
                if (k.cls == d.cls && iou(k, d) > iouTh) {
                    ok = false;
                    break;
                }
            }
            if (ok) keep.add(d);
            if (keep.size() >= 50) break;
        }
        return keep;
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
