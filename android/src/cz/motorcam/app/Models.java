package cz.motorcam.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Modely TensorFlow Lite: detektor děr (YOLO) a klasifikátor povrchu.
 *
 * Očekávaný formát (viz src/demo/export_tflite.py):
 *  - detektor: vstup [1, S, S, 3] float32 RGB 0–1 (letterbox), výstup [1, 4+nc, N]
 *  - povrch:   vstup [1, H, W, 3] float32 RGB 0–1 (normalizace je uvnitř modelu),
 *              výstup [1, 5] pravděpodobnosti (nebo logity – projdou softmaxem)
 * Názvy tříd se čtou z metadat ultralytics (metadata.json v ZIPu na konci souboru).
 */
public final class Models {
    public static final String[] DEFAULT_DET_NAMES = {"díra", "trhlina"};
    public static final String[] SURFACE_KEYS = {"asfalt", "rozbity_asfalt", "sterk", "hlina_blato", "mokro"};

    // volatile: UI vlákno se jen ptá, jestli model je, a nesmí čekat na běžící inferenci
    private volatile Interpreter det, surf;
    private int detSize;
    private int surfW, surfH;
    private float[][][] detOut;
    private float[][] surfOut;
    private int[] surfMap;            // index výstupu -> naše třída
    private ByteBuffer detIn, surfIn;
    private boolean detUint8, surfUint8;
    private Bitmap letterbox, surfCrop;
    private int[] pixels;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    public volatile String[] detNames = DEFAULT_DET_NAMES;
    public volatile boolean[] potholeClass = {true, false};
    public String detInfo = "", surfInfo = "";

    public boolean hasDetector() {
        return det != null;
    }

    public boolean hasSurface() {
        return surf != null;
    }

    private static MappedByteBuffer map(File f) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        FileChannel ch = raf.getChannel();
        MappedByteBuffer b = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        raf.close();
        return b;
    }

    private static Interpreter.Options options() {
        Interpreter.Options o = new Interpreter.Options();
        o.setNumThreads(4);
        return o;
    }

    /** Načte detektor děr. Vrací popis, nebo vyhodí výjimku se srozumitelnou zprávou. */
    public synchronized String loadDetector(File f) throws Exception {
        closeDetector();
        Interpreter it = new Interpreter(map(f), options());
        Tensor in = it.getInputTensor(0), out = it.getOutputTensor(0);
        int[] is = in.shape(), os = out.shape();
        if (is.length != 4 || is[3] != 3 || is[1] != is[2]) {
            it.close();
            throw new Exception("Nečekaný vstup detektoru " + shape(is) + " (čekám [1,S,S,3])");
        }
        if (os.length != 3 || out.dataType() != DataType.FLOAT32) {
            it.close();
            throw new Exception("Nečekaný výstup detektoru " + shape(os) + " (čekám [1,4+nc,N] float32)");
        }
        detSize = is[1];
        detUint8 = in.dataType() == DataType.UINT8;
        detIn = ByteBuffer.allocateDirect(detSize * detSize * 3 * (detUint8 ? 1 : 4)).order(ByteOrder.nativeOrder());
        detOut = new float[1][os[1]][os[2]];
        int nc = Math.min(os[1], os[2]) - 4;
        String[] names = readNames(f);
        if (names == null || names.length != nc) {
            names = nc == DEFAULT_DET_NAMES.length ? DEFAULT_DET_NAMES : generic(nc);
        }
        detNames = names;
        potholeClass = new boolean[nc];
        for (int i = 0; i < nc; i++) {
            String n = names[i].toLowerCase();
            potholeClass[i] = nc == 1 || n.contains("dira") || n.contains("díra") || n.contains("pothole")
                    || n.contains("vytluk") || n.contains("výtluk") || n.equals("d40");
            if (n.equals("dira")) names[i] = "díra";
        }
        letterbox = Bitmap.createBitmap(detSize, detSize, Bitmap.Config.ARGB_8888);
        det = it;
        detInfo = "vstup " + detSize + " px, třídy: " + join(names);
        return detInfo;
    }

    /** Načte klasifikátor povrchu. */
    public synchronized String loadSurface(File f) throws Exception {
        closeSurface();
        Interpreter it = new Interpreter(map(f), options());
        Tensor in = it.getInputTensor(0), out = it.getOutputTensor(0);
        int[] is = in.shape(), os = out.shape();
        if (is.length != 4 || is[3] != 3) {
            it.close();
            throw new Exception("Nečekaný vstup modelu povrchu " + shape(is) + " (čekám [1,H,W,3])");
        }
        int nc = os[os.length - 1];
        if (nc != SURFACE_KEYS.length || out.dataType() != DataType.FLOAT32) {
            it.close();
            throw new Exception("Model povrchu musí mít 5 výstupů float32, má " + shape(os));
        }
        surfH = is[1];
        surfW = is[2];
        surfUint8 = in.dataType() == DataType.UINT8;
        surfIn = ByteBuffer.allocateDirect(surfW * surfH * 3 * (surfUint8 ? 1 : 4)).order(ByteOrder.nativeOrder());
        surfOut = new float[1][nc];
        // pořadí tříd podle metadat (pokud jsou), jinak předpokládáme naše pořadí
        surfMap = new int[nc];
        String[] names = readNames(f);
        for (int i = 0; i < nc; i++) {
            surfMap[i] = i;
            if (names != null && names.length == nc) {
                for (int k = 0; k < nc; k++) if (SURFACE_KEYS[k].equals(names[i])) surfMap[i] = k;
            }
        }
        surfCrop = Bitmap.createBitmap(surfW, surfH, Bitmap.Config.ARGB_8888);
        surf = it;
        surfInfo = "vstup " + surfW + "×" + surfH + " px";
        return surfInfo;
    }

    /** Detekce děr na snímku (souřadnice výsledku 0–1 vůči snímku). */
    public synchronized List<Detection> detect(Bitmap frame, float confTh) {
        if (det == null) return null;
        int fw = frame.getWidth(), fh = frame.getHeight();
        float scale = Math.min((float) detSize / fw, (float) detSize / fh);
        float nw = fw * scale, nh = fh * scale;
        float padX = (detSize - nw) / 2, padY = (detSize - nh) / 2;
        Canvas c = new Canvas(letterbox);
        c.drawColor(Color.rgb(114, 114, 114));   // šedé okraje jako v ultralytics
        c.drawBitmap(frame, null, new android.graphics.RectF(padX, padY, padX + nw, padY + nh), paint);
        fill(detIn, letterbox, detSize, detSize, detUint8);
        det.run(detIn, detOut);
        return Detection.decodeYolo(detOut[0], detSize, padX, padY, scale, fw, fh, confTh, 0.5f);
    }

    /** Klasifikace povrchu z výřezu spodní části snímku (silnice těsně před motorkou). */
    public synchronized float[] classify(Bitmap frame) {
        if (surf == null) return null;
        int fw = frame.getWidth(), fh = frame.getHeight();
        Rect src = new Rect((int) (fw * 0.2f), (int) (fh * 0.55f), (int) (fw * 0.8f), fh);
        Canvas c = new Canvas(surfCrop);
        c.drawBitmap(frame, src, new Rect(0, 0, surfW, surfH), paint);
        fill(surfIn, surfCrop, surfW, surfH, surfUint8);
        surf.run(surfIn, surfOut);
        float[] raw = surfOut[0];
        // softmax, pokud výstup nejsou pravděpodobnosti
        float sum = 0, min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : raw) {
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        float[] p = new float[raw.length];
        if (min < 0 || Math.abs(sum - 1) > 0.05f) {
            float z = 0;
            for (int i = 0; i < raw.length; i++) z += (float) Math.exp(raw[i] - max);
            for (int i = 0; i < raw.length; i++) p[surfMap[i]] = (float) Math.exp(raw[i] - max) / z;
        } else {
            for (int i = 0; i < raw.length; i++) p[surfMap[i]] = raw[i];
        }
        return p;
    }

    private void fill(ByteBuffer buf, Bitmap bmp, int w, int h, boolean uint8) {
        if (pixels == null || pixels.length < w * h) pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        buf.rewind();
        for (int i = 0; i < w * h; i++) {
            int px = pixels[i];
            if (uint8) {
                buf.put((byte) ((px >> 16) & 0xFF));
                buf.put((byte) ((px >> 8) & 0xFF));
                buf.put((byte) (px & 0xFF));
            } else {
                buf.putFloat(((px >> 16) & 0xFF) / 255f);
                buf.putFloat(((px >> 8) & 0xFF) / 255f);
                buf.putFloat((px & 0xFF) / 255f);
            }
        }
        buf.rewind();
    }

    /** Názvy tříd z metadat ultralytics ({0: 'dira', 1: 'trhlina'} nebo {"0": "dira"}). */
    static String[] readNames(File f) {
        String text = null;
        try {
            ZipFile zf = new ZipFile(f);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.getName().endsWith(".json") || e.getName().endsWith(".txt") || e.getName().endsWith(".yaml")) {
                    InputStream in = zf.getInputStream(e);
                    text = new String(MapService.readAll(in), StandardCharsets.UTF_8);
                    if (text.contains("names")) break;
                }
            }
            zf.close();
        } catch (Exception ignored) {
            // není ZIP – zkusíme hledat přímo v bajtech (starší exporty)
        }
        if (text == null || !text.contains("names")) {
            try {
                RandomAccessFile raf = new RandomAccessFile(f, "r");
                long len = raf.length();
                int n = (int) Math.min(len, 65536);
                byte[] b = new byte[n];
                raf.seek(len - n);
                raf.readFully(b);
                raf.close();
                text = new String(b, StandardCharsets.ISO_8859_1);
            } catch (Exception e) {
                return null;
            }
        }
        int at = text.indexOf("names");
        if (at < 0) return null;
        int open = text.indexOf('{', at), close = text.indexOf('}', open);
        if (open < 0 || close < 0) return null;
        Matcher m = Pattern.compile("['\"]?(\\d+)['\"]?\\s*:\\s*['\"]([^'\"]+)['\"]").matcher(text.substring(open, close));
        List<String> names = new ArrayList<>();
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            while (names.size() <= idx) names.add("třída " + names.size());
            names.set(idx, m.group(2));
        }
        return names.isEmpty() ? null : names.toArray(new String[0]);
    }

    private static String[] generic(int n) {
        String[] s = new String[n];
        for (int i = 0; i < n; i++) s[i] = "třída " + i;
        return s;
    }

    private static String shape(int[] s) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < s.length; i++) b.append(i > 0 ? "," : "").append(s[i]);
        return b.append("]").toString();
    }

    private static String join(String[] s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length; i++) b.append(i > 0 ? ", " : "").append(s[i]);
        return b.toString();
    }

    public synchronized void closeDetector() {
        if (det != null) det.close();
        det = null;
    }

    public synchronized void closeSurface() {
        if (surf != null) surf.close();
        surf = null;
    }
}
