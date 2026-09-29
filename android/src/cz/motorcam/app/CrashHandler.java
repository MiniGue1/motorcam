package cz.motorcam.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Když aplikace spadne, uloží chybu do souboru a při dalším spuštění ji zobrazí,
 * aby se dala zkopírovat a opravit.
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {
    private final File file;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashHandler(Context c) {
        file = new File(c.getFilesDir(), "crash.txt");
        previous = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void install(Context c) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(c.getApplicationContext()));
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            FileOutputStream out = new FileOutputStream(file);
            out.write(("Vlákno: " + t.getName() + "\n" + sw).getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
        }
        if (previous != null) previous.uncaughtException(t, e);
    }

    /** Vrátí text poslední chyby (a soubor smaže), nebo null. */
    public static String takeLast(Context c) {
        File f = new File(c.getFilesDir(), "crash.txt");
        if (!f.exists()) return null;
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 20000)];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(b);
            in.close();
            f.delete();
            return new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
