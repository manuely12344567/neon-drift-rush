package com.neondrift.rush;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.neondrift.rush.core.Engine;
import com.neondrift.rush.core.Events;

/**
 * The game surface: owns the render thread, the input handling and the
 * screen-to-screen state machine (menu, playing, paused, game over).
 */
public final class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    private static final int MODE_MENU = NeonRenderer.MODE_MENU;
    private static final int MODE_PLAY = NeonRenderer.MODE_PLAY;
    private static final int MODE_PAUSED = NeonRenderer.MODE_PAUSED;
    private static final int MODE_OVER = NeonRenderer.MODE_OVER;

    private static final float CRASH_TO_OVER = 1.15f;
    private static final long FRAME_NS = 16666666L;

    private final Engine engine;
    private final NeonRenderer renderer = new NeonRenderer();
    private final Prefs prefs;
    private final Sfx sfx;

    private Thread thread;
    private volatile boolean running;
    private volatile boolean surfaceReady;
    private volatile boolean activityPaused;

    private int mode = MODE_MENU;
    private float anim;
    private float overTime;
    private double accumulator;

    private boolean dragging;
    private float grabOffset;
    /** Locking the surface through the GPU is much faster on low-end phones. */
    private boolean hardwareCanvas = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;

    public GameView(Context context) {
        super(context);
        prefs = new Prefs(context);
        sfx = new Sfx(context);
        engine = new Engine(System.currentTimeMillis());
        engine.demo = true;
        engine.reset(prefs.best());

        sfx.setSoundOn(prefs.sound());
        sfx.setMusicOn(prefs.music());

        getHolder().addCallback(this);
        setFocusable(true);
        setKeepScreenOn(true);
    }

    // -------------------------------------------------------------- lifecycle

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        startLoop();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        renderer.resize(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        stopLoop();
    }

    /** Called from the activity onPause. */
    public void onActivityPause() {
        activityPaused = true;
        if (mode == MODE_PLAY) {
            mode = MODE_PAUSED;
        }
        sfx.pauseMusic();
    }

    /** Called from the activity onResume. */
    public void onActivityResume() {
        activityPaused = false;
        sfx.setMusicOn(prefs.music());
        sfx.startMusic();
    }

    /** Called from the activity onDestroy. */
    public void onActivityDestroy() {
        sfx.release();
    }

    /** Back button handling. Returns true when the event was consumed. */
    public boolean handleBack() {
        if (mode == MODE_PLAY) {
            mode = MODE_PAUSED;
            sfx.play(Sfx.TAP);
            return true;
        }
        if (mode == MODE_PAUSED || mode == MODE_OVER) {
            goToMenu();
            return true;
        }
        return false;
    }

    private void startLoop() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this, "neon-drift-loop");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        sfx.startMusic();
    }

    private void stopLoop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        sfx.pauseMusic();
    }

    // ------------------------------------------------------------------- loop

    @Override
    public void run() {
        long last = System.nanoTime();
        while (running) {
            long frameStart = System.nanoTime();
            float dt = (frameStart - last) / 1e9f;
            last = frameStart;
            if (dt > 0.05f) {
                dt = 0.05f;
            }
            if (!activityPaused) {
                step(dt);
            }
            drawFrame(activityPaused ? 0f : dt);

            long spent = System.nanoTime() - frameStart;
            long wait = FRAME_NS - spent;
            if (wait > 1000000L) {
                try {
                    Thread.sleep(wait / 1000000L, (int) (wait % 1000000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void drawFrame(float dt) {
        if (!surfaceReady) {
            return;
        }
        Canvas canvas = null;
        SurfaceHolder holder = getHolder();
        try {
            if (hardwareCanvas) {
                try {
                    canvas = holder.lockHardwareCanvas();
                } catch (Throwable unsupported) {
                    hardwareCanvas = false;
                    canvas = holder.lockCanvas();
                }
            } else {
                canvas = holder.lockCanvas();
            }
            if (canvas == null) {
                return;
            }
            synchronized (this) {
                renderer.draw(canvas, engine, mode, anim, dt, prefs.sound(), prefs.music(),
                        overTime);
            }
        } catch (IllegalStateException ignored) {
            // surface went away under our feet
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas);
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }

    private void step(float dt) {
        anim += dt;
        switch (mode) {
            case MODE_MENU:
                engine.demo = true;
                engine.setTargetX(engine.nearestOrbX());
                engine.update(dt);
                break;
            case MODE_PLAY:
                engine.demo = false;
                engine.update(dt);
                drainEvents();
                if (!engine.alive) {
                    overTime += dt;
                    if (overTime >= CRASH_TO_OVER) {
                        finishRun();
                    }
                }
                break;
            case MODE_OVER:
                overTime += dt;
                engine.update(dt); // keeps the world breathing behind the panel
                renderer.handleEvents(engine);
                break;
            default:
                break;
        }
    }

    private void drainEvents() {
        renderer.handleEvents(engine);
        Events ev = engine.events;
        for (int i = 0; i < ev.size(); i++) {
            switch (ev.type(i)) {
                case Events.ORB_PICKED:
                    sfx.play(Sfx.PICK);
                    break;
                case Events.NEAR_MISS:
                    sfx.play(Sfx.NEAR);
                    break;
                case Events.CRASH:
                    sfx.play(Sfx.CRASH);
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    break;
                case Events.NEW_RECORD:
                    sfx.play(Sfx.RECORD);
                    break;
                default:
                    break;
            }
        }
    }

    // ------------------------------------------------------------- transitions

    private void startRun() {
        engine.demo = false;
        engine.reset(prefs.best());
        renderer.clearParticles();
        overTime = 0f;
        mode = MODE_PLAY;
        sfx.play(Sfx.TAP);
        prefs.addRun();
    }

    private void finishRun() {
        int score = engine.score();
        if (score > prefs.best()) {
            prefs.setBest(score);
        }
        prefs.addOrbs(engine.orbs);
        engine.best = prefs.best();
        overTime = 0f;
        mode = MODE_OVER;
    }

    private void goToMenu() {
        engine.demo = true;
        engine.reset(prefs.best());
        renderer.clearParticles();
        overTime = 0f;
        mode = MODE_MENU;
        sfx.play(Sfx.TAP);
    }

    private void toggleMusic() {
        boolean on = !prefs.music();
        prefs.setMusic(on);
        sfx.setMusicOn(on);
        if (on) {
            sfx.startMusic();
        }
        sfx.play(Sfx.TAP);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void toggleSound() {
        boolean on = !prefs.sound();
        prefs.setSound(on);
        sfx.setSoundOn(on);
        if (on) {
            sfx.play(Sfx.TAP);
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onDown(x, y);
            case MotionEvent.ACTION_MOVE:
                if (mode == MODE_PLAY && dragging) {
                    engine.setTargetX(renderer.lateralAt(x) + grabOffset);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private boolean onDown(float x, float y) {
        switch (mode) {
            case MODE_MENU:
                if (renderer.soundButton().contains(x, y)) {
                    toggleSound();
                    return true;
                }
                if (renderer.musicButton().contains(x, y)) {
                    toggleMusic();
                    return true;
                }
                if (renderer.playButton().contains(x, y) || y < getHeight() * 0.94f) {
                    startRun();
                    return true;
                }
                return true;

            case MODE_PLAY:
                if (renderer.pauseButton().contains(x, y)) {
                    mode = MODE_PAUSED;
                    sfx.play(Sfx.TAP);
                    return true;
                }
                dragging = true;
                grabOffset = engine.playerX - renderer.lateralAt(x);
                grabOffset = Math.max(-0.6f, Math.min(0.6f, grabOffset));
                engine.setTargetX(renderer.lateralAt(x) + grabOffset);
                return true;

            case MODE_PAUSED:
                if (renderer.retryButton().contains(x, y)) {
                    mode = MODE_PLAY;
                    sfx.play(Sfx.TAP);
                } else if (renderer.menuButton().contains(x, y)) {
                    goToMenu();
                }
                return true;

            case MODE_OVER:
                if (overTime < 0.35f) {
                    return true; // ignore the tap that killed the run
                }
                if (renderer.retryButton().contains(x, y)) {
                    startRun();
                } else if (renderer.menuButton().contains(x, y)) {
                    goToMenu();
                }
                return true;

            default:
                return true;
        }
    }
}
