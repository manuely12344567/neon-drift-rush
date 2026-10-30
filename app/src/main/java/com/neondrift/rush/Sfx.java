package com.neondrift.rush;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;

/** Sound effects (SoundPool) and the ambient music loop (MediaPlayer). */
public final class Sfx {

    public static final int PICK = 0;
    public static final int CRASH = 1;
    public static final int NEAR = 2;
    public static final int TAP = 3;
    public static final int RECORD = 4;

    private final SoundPool pool;
    private final int[] ids = new int[5];
    private MediaPlayer music;

    private boolean soundOn = true;
    private boolean musicOn = true;
    private int loaded;
    private boolean musicReleased;

    public Sfx(Context context) {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(attrs)
                .build();
        pool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                if (status == 0) {
                    loaded++;
                }
            }
        });
        ids[PICK] = pool.load(context, R.raw.sfx_pick, 1);
        ids[CRASH] = pool.load(context, R.raw.sfx_crash, 1);
        ids[NEAR] = pool.load(context, R.raw.sfx_near, 1);
        ids[TAP] = pool.load(context, R.raw.sfx_tap, 1);
        ids[RECORD] = pool.load(context, R.raw.sfx_record, 1);

        try {
            music = MediaPlayer.create(context, R.raw.music_loop);
            if (music != null) {
                music.setLooping(true);
                music.setVolume(0.30f, 0.30f);
            }
        } catch (Throwable ignored) {
            music = null;
        }
    }

    public void setSoundOn(boolean on) {
        soundOn = on;
    }

    public void setMusicOn(boolean on) {
        musicOn = on;
        if (music == null) {
            return;
        }
        try {
            if (on && !music.isPlaying()) {
                music.start();
            } else if (!on && music.isPlaying()) {
                music.pause();
            }
        } catch (IllegalStateException ignored) {
            // player was released, nothing to do
        }
    }

    /** Plays one of the five effects. Never throws. */
    public void play(int kind) {
        if (!soundOn || loaded < ids.length) {
            return;
        }
        try {
            float rate = 1f;
            float volume = 1f;
            switch (kind) {
                case PICK:
                    rate = 0.94f + (float) Math.random() * 0.16f;
                    volume = 0.85f;
                    break;
                case CRASH:
                    volume = 1f;
                    break;
                case NEAR:
                    rate = 1.0f + (float) Math.random() * 0.1f;
                    volume = 0.55f;
                    break;
                case TAP:
                    volume = 0.65f;
                    break;
                default:
                    volume = 0.9f;
                    break;
            }
            pool.play(ids[kind], volume, volume, 1, 0, rate);
        } catch (Throwable ignored) {
            // audio is a nicety: never let it break the game
        }
    }

    public void startMusic() {
        if (!musicOn || music == null) {
            return;
        }
        try {
            if (!music.isPlaying()) {
                music.start();
            }
        } catch (IllegalStateException ignored) {
        }
    }

    public void pauseMusic() {
        if (music == null) {
            return;
        }
        try {
            if (music.isPlaying()) {
                music.pause();
            }
        } catch (IllegalStateException ignored) {
        }
    }

    public void release() {
        if (music != null && !musicReleased) {
            musicReleased = true;
            try {
                music.stop();
            } catch (IllegalStateException ignored) {
            }
            music.release();
            music = null;
        }
        pool.release();
    }
}
