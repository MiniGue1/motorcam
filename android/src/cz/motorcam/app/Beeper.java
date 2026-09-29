package cz.motorcam.app;

import android.media.AudioManager;
import android.media.ToneGenerator;

/**
 * Pípání podle stupně varování (běží ve vlastním vlákně):
 *   0 = ticho, 1 = jedno pípnutí za ~1,5 s (zpomal mírně), 2 = rychlé pípání (zpomal hodně).
 * Zvuk jde do STREAM_MUSIC, aby šel i do Bluetooth interkomu v helmě.
 */
public final class Beeper implements Runnable {
    private volatile int level;
    private volatile boolean running = true;
    private volatile int pendingAlerts;
    private ToneGenerator tone;
    public volatile boolean muted;

    public Beeper() {
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        } catch (RuntimeException e) {
            tone = null;   // některá zařízení ToneGenerator neumí – aplikace poběží bez zvuku
        }
        Thread t = new Thread(this, "pipani");
        t.setDaemon(true);
        t.start();
    }

    public void setLevel(int level) {
        this.level = level;
    }

    /** Krátké dvojité pípnutí (např. díra před motorkou). */
    public void alert() {
        pendingAlerts = 1;
    }

    public void release() {
        running = false;
    }

    private void beep(int ms) {
        if (tone != null && !muted) tone.startTone(ToneGenerator.TONE_PROP_BEEP, ms);
    }

    @Override
    public void run() {
        try {
            while (running) {
                if (pendingAlerts > 0) {
                    pendingAlerts = 0;
                    beep(70);
                    Thread.sleep(140);
                    beep(70);
                    Thread.sleep(300);
                    continue;
                }
                int l = level;
                if (l >= 2) {
                    beep(80);
                    Thread.sleep(230);
                } else if (l == 1) {
                    beep(160);
                    // během pauzy průběžně kontrolujeme, jestli stupeň nevzrostl
                    for (int i = 0; i < 14 && level == 1 && running; i++) Thread.sleep(100);
                } else {
                    Thread.sleep(80);
                }
            }
        } catch (InterruptedException ignored) {
        } finally {
            if (tone != null) tone.release();
        }
    }
}
