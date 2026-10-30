package com.neondrift.rush;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.neondrift.rush.core.Engine;
import com.neondrift.rush.core.Events;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The game surface: owns the render thread, the input handling and the
 * screen-to-screen state machine (menu, playing, paused, game over).
 *
 * <h3>Threading contract</h3>
 * Everything that touches the simulation ({@link Engine}) or the screen state is
 * executed on the render thread. The UI thread may only:
 * <ul>
 *   <li>post a command ({@link #CMD_START}, {@link #CMD_PAUSE}, ...) through an
 *       {@link AtomicInteger},</li>
 *   <li>write the requested lateral position ({@link #inputTargetX}),</li>
 *   <li>read {@code playerX} and the button rectangles for hit testing.</li>
 * </ul>
 * Before this was enforced, tapping "JOUER" called {@code engine.reset()} from
 * the UI thread while the render thread was iterating the entity list, which
 * could tear the {@code ArrayList} and kill the process.
 */
public final class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    private static final String TAG = "NeonDrift";

    private static final int MODE_MENU = NeonRenderer.MODE_MENU;
    private static final int MODE_PLAY = NeonRenderer.MODE_PLAY;
    private static final int MODE_PAUSED = NeonRenderer.MODE_PAUSED;
    private static final int MODE_OVER = NeonRenderer.MODE_OVER;

    private static final int CMD_NONE = 0;
    private static final int CMD_START = 1;
    private static final int CMD_PAUSE = 2;
    private static final int CMD_RESUME = 3;
    private static final int CMD_MENU = 4;

    private static final float CRASH_TO_OVER = 1.15f;
    private static final long FRAME_NS = 16666666L;

    private final Engine engine;
    private final NeonRenderer renderer = new NeonRenderer();
    private final Prefs prefs;
    private final Sfx sfx;

    /** Command posted by the UI thread, consumed by the render thread. */
    private final AtomicInteger command = new AtomicInteger(CMD_NONE);
    /** Lateral position requested by the finger (render thread applies it). */
    private volatile float inputTargetX;

    private Thread thread;
    private volatile boolean running;
    private volatile boolean surfaceReady;
    private volatile boolean activityPaused;
    /** GPU canvas is much faster, but fall back to software if it misbehaves. */
    private volatile boolean hardwareCanvas = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;

    // ----- state owned by the render thread (volatile: read by the UI thread)
    private volatile int mode = MODE_MENU;
    private volatile float overTime;
    private float anim;
    private boolean dragging;
    private float grabOffset;
    /** Set when drawing failed for good: shows a diagnostic screen. */
    private volatile String fatalError;

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
        // Only a request: the render thread applies it when it is safe to do so.
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
            command.set(CMD_PAUSE);
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
        stopLoop();
        sfx.release();
    }

    /** Back button handling. Returns true when the event was consumed. */
    public boolean handleBack() {
        if (mode == MODE_PLAY) {
            command.set(CMD_PAUSE);
            sfx.play(Sfx.TAP);
            return true;
        }
        if (mode == MODE_PAUSED || mode == MODE_OVER) {
            command.set(CMD_MENU);
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
        // NORM+1: a frame loop must not starve the UI thread, otherwise the
        // system may consider the app unresponsive on slower devices.
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
        sfx.startMusic();
    }

    private void stopLoop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(500);
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
            try {
                applyCommand();
                if (!activityPaused && fatalError == null) {
                    step(dt);
                    drawFrame(dt);
                }
            } catch (Throwable t) {
                // A frame must never be able to kill the app: recover once by
                // falling back to the software canvas, then show a readable
                // diagnostic instead of "l'application s'est arrêtée".
                if (hardwareCanvas) {
                    hardwareCanvas = false;
                    Log.w(TAG, "Hardware canvas failed, falling back to software", t);
                } else {
                    fail(t);
                }
            }

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

    private void applyCommand() {
        int cmd = command.getAndSet(CMD_NONE);
        switch (cmd) {
            case CMD_START:
                startRun();
                break;
            case CMD_PAUSE:
                if (mode == MODE_PLAY) {
                    mode = MODE_PAUSED;
                }
                break;
            case CMD_RESUME:
                if (mode == MODE_PAUSED) {
                    mode = MODE_PLAY;
                }
                break;
            case CMD_MENU:
                goToMenu();
                break;
            default:
                break;
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
                canvas = holder.lockHardwareCanvas();
            } else {
                canvas = holder.lockCanvas();
            }
            if (canvas == null) {
                return;
            }
            if (fatalError != null) {
                drawDiagnostic(canvas);
            } else {
                renderer.draw(canvas, engine, mode, anim, dt, prefs.sound(), prefs.music(),
                        overTime);
            }
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas);
                } catch (Throwable ignored) {
                    // surface gone
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
                engine.setTargetX(inputTargetX);
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
                    postHaptic(HapticFeedbackConstants.LONG_PRESS);
                    break;
                case Events.NEW_RECORD:
                    sfx.play(Sfx.RECORD);
                    break;
                default:
                    break;
            }
        }
    }

    private void postHaptic(final int feedback) {
        post(new Runnable() {
            @Override
            public void run() {
                performHapticFeedback(feedback);
            }
        });
    }

    // ------------------------------------------------------------- transitions

    private void startRun() {
        engine.demo = false;
        engine.reset(prefs.best());
        renderer.clearParticles();
        inputTargetX = 0f;
        dragging = false;
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

    // --------------------------------------------------------------- failures

    /** Records the error, stops the loop and shows it on screen. */
    private void fail(Throwable t) {
        Log.e(TAG, "Fatal error in the game loop", t);
        StringBuilder sb = new StringBuilder();
        sb.append(t.toString()).append('\n');
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 8; i++) {
            sb.append("  at ").append(st[i].toString()).append('\n');
        }
        saveError(sb.toString());
        fatalError = sb.toString();
        running = false;
    }

    private void saveError(String text) {
        FileWriter writer = null;
        try {
            File dir = getContext().getFilesDir();
            writer = new FileWriter(new File(dir, "last_error.txt"));
            writer.write(text);
        } catch (IOException ignored) {
            // nothing else we can do
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Minimal, dependency-free screen (it must not touch the renderer). */
    private void drawDiagnostic(Canvas c) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawColor(0xFF12061F);
        int width = c.getWidth();
        float size = Math.max(18f, width * 0.032f);
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(size);
        p.setColor(0xFFFF6B8A);
        float y = width * 0.10f;
        c.drawText("NEON DRIFT - erreur", width * 0.05f, y, p);
        p.setColor(0xFFEAF6FF);
        y += size * 2f;
        String[] lines = fatalError.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            while (line.length() > 0) {
                int cut = Math.min(line.length(), (int) (width / (size * 0.62f)));
                c.drawText(line.substring(0, cut), width * 0.05f, y, p);
                line = line.substring(cut);
                y += size * 1.35f;
            }
        }
        p.setColor(0xFF93A9C6);
        c.drawText("Detail enregistre dans last_error.txt", width * 0.05f, y + size, p);
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
                if (dragging) {
                    inputTargetX = clampTarget(renderer.lateralAt(x) + grabOffset);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    private static float clampTarget(float v) {
        if (v < -Engine.TRACK_LIMIT) {
            return -Engine.TRACK_LIMIT;
        }
        return v > Engine.TRACK_LIMIT ? Engine.TRACK_LIMIT : v;
    }

    private boolean onDown(float x, float y) {
        if (fatalError != null) {
            return true;
        }
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
                command.set(CMD_START);
                return true;

            case MODE_PLAY:
                if (renderer.pauseButton().contains(x, y)) {
                    command.set(CMD_PAUSE);
                    sfx.play(Sfx.TAP);
                    return true;
                }
                dragging = true;
                grabOffset = engine.playerX - renderer.lateralAt(x);
                grabOffset = Math.max(-0.6f, Math.min(0.6f, grabOffset));
                inputTargetX = clampTarget(renderer.lateralAt(x) + grabOffset);
                return true;

            case MODE_PAUSED:
                if (renderer.retryButton().contains(x, y)) {
                    command.set(CMD_RESUME);
                    sfx.play(Sfx.TAP);
                } else if (renderer.menuButton().contains(x, y)) {
                    command.set(CMD_MENU);
                }
                return true;

            case MODE_OVER:
                if (overTime < 0.35f) {
                    return true; // ignore the tap that killed the run
                }
                if (renderer.retryButton().contains(x, y)) {
                    command.set(CMD_START);
                } else if (renderer.menuButton().contains(x, y)) {
                    command.set(CMD_MENU);
                }
                return true;

            default:
                return true;
        }
    }
}
