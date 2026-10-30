package com.neondrift.rush;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny wrapper around SharedPreferences for the persistent state. */
public final class Prefs {

    private static final String FILE = "neon_drift";
    private static final String KEY_BEST = "best_score";
    private static final String KEY_SOUND = "sound_on";
    private static final String KEY_MUSIC = "music_on";
    private static final String KEY_RUNS = "runs";
    private static final String KEY_TOTAL_ORBS = "total_orbs";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int best() {
        return prefs.getInt(KEY_BEST, 0);
    }

    public void setBest(int value) {
        prefs.edit().putInt(KEY_BEST, value).apply();
    }

    public int runs() {
        return prefs.getInt(KEY_RUNS, 0);
    }

    public void addRun() {
        prefs.edit().putInt(KEY_RUNS, runs() + 1).apply();
    }

    public int totalOrbs() {
        return prefs.getInt(KEY_TOTAL_ORBS, 0);
    }

    public void addOrbs(int n) {
        prefs.edit().putInt(KEY_TOTAL_ORBS, totalOrbs() + n).apply();
    }

    public boolean sound() {
        return prefs.getBoolean(KEY_SOUND, true);
    }

    public void setSound(boolean on) {
        prefs.edit().putBoolean(KEY_SOUND, on).apply();
    }

    public boolean music() {
        return prefs.getBoolean(KEY_MUSIC, true);
    }

    public void setMusic(boolean on) {
        prefs.edit().putBoolean(KEY_MUSIC, on).apply();
    }
}
