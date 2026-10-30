package com.neondrift.rush;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.neondrift.rush.core.Engine;
import com.neondrift.rush.core.Entity;
import com.neondrift.rush.core.Events;
import com.neondrift.rush.core.Perspective;

import java.util.Random;

/**
 * All the drawing of NEON DRIFT.
 *
 * <p>Everything is rendered with plain {@link Canvas} calls on a hardware
 * accelerated SurfaceView: no third-party library, no asset atlas, and the
 * expensive static layers (sky, sun, skyline, scanlines) are cached in bitmaps
 * so a 60 fps budget stays comfortable even on modest phones.
 */
public final class NeonRenderer {

    public static final int MODE_MENU = 0;
    public static final int MODE_PLAY = 1;
    public static final int MODE_PAUSED = 2;
    public static final int MODE_OVER = 3;

    private static final int MAX_PARTICLES = 300;
    private static final float GRID_STEP = 10f; // world units between two grid lines
    private static final float[] GROUND_LINES = {-4.6f, -3.0f, -2.1f, -1.5f,
            1.5f, 2.1f, 3.0f, 4.6f};

    private final Perspective persp = new Perspective();
    private float w = 1080f;
    private float h = 2400f;
    private float unit = 1f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint layer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path path2 = new Path();
    private final Path ship = new Path();
    private final RectF rect = new RectF();
    private final RectF rect2 = new RectF();

    private Bitmap background;
    private Bitmap skyline;
    /** Size requested by the view; applied by the render thread (see below). */
    private volatile int pendingWidth = 1080;
    private volatile int pendingHeight = 2400;
    private int appliedWidth = -1;
    private int appliedHeight = -1;
    /** Cached full-screen gradients, rebuilt only when the size changes. */
    private Shader groundShader;
    private Shader roadShader;
    private Shader scanShader;
    private Shader vignette;
    private Shader skyFallback;

    private Typeface tfBlack;
    private Typeface tfCondensed;
    private Typeface tfMono;

    // UI layout, recomputed on resize
    private final RectF btnPlay = new RectF();
    private final RectF btnRetry = new RectF();
    private final RectF btnMenu = new RectF();
    private final RectF btnPause = new RectF();
    private final RectF btnSound = new RectF();
    private final RectF btnMusic = new RectF();

    // particles (struct of arrays: no per-frame allocation)
    private final float[] pX = new float[MAX_PARTICLES];
    private final float[] pY = new float[MAX_PARTICLES];
    private final float[] pVX = new float[MAX_PARTICLES];
    private final float[] pVY = new float[MAX_PARTICLES];
    private final float[] pLife = new float[MAX_PARTICLES];
    private final float[] pMax = new float[MAX_PARTICLES];
    private final float[] pSize = new float[MAX_PARTICLES];
    private final float[] pDrag = new float[MAX_PARTICLES];
    private final int[] pColor = new int[MAX_PARTICLES];
    private final boolean[] pScreen = new boolean[MAX_PARTICLES];
    private int particleCount;
    private final Random fX = new Random(4242L);

    private float shake;
    private float flash;
    private float hitStop;
    private float deathFx;
    private int lastCombo = 1;
    private float comboPop;

    public NeonRenderer() {
        tfBlack = Typeface.create("sans-serif-black", Typeface.NORMAL);
        tfCondensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        tfMono = Typeface.create("monospace", Typeface.BOLD);
    }

    // ------------------------------------------------------------------ layout

    /**
     * Requests a new drawing size.
     *
     * <p>Safe to call from any thread (the view calls it from
     * {@code surfaceChanged}, i.e. the UI thread): this only records the
     * request. The expensive part - rebuilding the cached bitmaps - is done by
     * the render thread at the beginning of its next frame, in
     * {@link #applyPendingSize()}.
     *
     * <p>This used to rebuild and {@code recycle()} the bitmaps right here,
     * which meant that a single window relayout (Android relayouts the surface
     * a second or two after launch, when the immersive mode hides the system
     * bars) could recycle a bitmap the render thread was drawing, killing the
     * process with "Canvas: trying to use a recycled bitmap". Never again.
     */
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        pendingWidth = width;
        pendingHeight = height;
    }

    /** Applies a pending size request. Render thread only. */
    private void applyPendingSize() {
        int newWidth = pendingWidth;
        int newHeight = pendingHeight;
        if (newWidth == appliedWidth && newHeight == appliedHeight) {
            return;
        }
        appliedWidth = newWidth;
        appliedHeight = newHeight;
        this.w = newWidth;
        this.h = newHeight;
        this.unit = newWidth / 1080f;
        persp.configure(this.w, this.h);

        buildBackground();
        buildSkyline();
        buildScanline();

        float bw = w * 0.62f;
        btnPlay.set((w - bw) * 0.5f, h * 0.665f, (w + bw) * 0.5f, h * 0.665f + h * 0.082f);
        float b2 = w * 0.68f;
        btnRetry.set((w - b2) * 0.5f, h * 0.60f, (w + b2) * 0.5f, h * 0.60f + h * 0.082f);
        btnMenu.set((w - b2) * 0.5f, h * 0.60f + h * 0.105f, (w + b2) * 0.5f,
                h * 0.60f + h * 0.105f + h * 0.082f);
        float bs = 46f * unit;
        btnPause.set(w - bs * 2.35f, bs * 0.55f, w - bs * 0.45f, bs * 1.45f);
        btnSound.set(w - bs * 4.0f, bs * 0.55f, w - bs * 2.1f, bs * 1.45f);
        btnMusic.set(w - bs * 5.7f, bs * 0.55f, w - bs * 3.8f, bs * 1.45f);
    }

    /** True once a size has been applied and the caches are ready. */
    public boolean isReady() {
        return appliedWidth > 0 && appliedHeight > 0;
    }

    public RectF playButton() {
        return btnPlay;
    }

    public RectF retryButton() {
        return btnRetry;
    }

    public RectF menuButton() {
        return btnMenu;
    }

    public RectF pauseButton() {
        return btnPause;
    }

    public RectF soundButton() {
        return btnSound;
    }

    public RectF musicButton() {
        return btnMusic;
    }

    /** Converts a screen X into a lateral track position at the player depth. */
    public float lateralAt(float screenX) {
        return persp.lateralAtPlayer(screenX);
    }

    private void buildBackground() {
        int iw = Math.max(1, (int) w);
        int ih = Math.max(1, (int) (persp.horizonY + h * 0.06f));
        Bitmap sky = null;
        try {
            sky = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError oom) {
            // very small heap: fall back to per-frame gradients, the game stays
            // playable instead of dying on a low-end device
            background = null;
            return;
        }
        Canvas c = new Canvas(sky);
        float horizon = persp.horizonY;

        // --- sky -------------------------------------------------------------
        skyFallback = new LinearGradient(0, 0, 0, horizon,
                new int[]{Palette.BG_TOP, Palette.BG_MID, Palette.BG_LOW},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        fill.setShader(skyFallback);
        c.drawRect(0, 0, w, horizon + 2f, fill);
        fill.setShader(null);

        // --- stars -----------------------------------------------------------
        Random rnd = new Random(20260101L);
        for (int i = 0; i < 130; i++) {
            float x = rnd.nextFloat() * w;
            float y = rnd.nextFloat() * horizon * 0.92f;
            float r = (0.6f + rnd.nextFloat() * 1.7f) * unit;
            int col = rnd.nextBoolean() ? Palette.WHITE : Palette.CITY_WINDOW_A;
            fill.setColor(Palette.alpha(col, 40 + rnd.nextInt(140)));
            c.drawCircle(x, y, r, fill);
        }

        // --- horizon glow ----------------------------------------------------
        fill.setShader(new RadialGradient(persp.centreX, horizon, w * 0.92f,
                new int[]{Palette.alpha(Palette.HORIZON, 190),
                        Palette.alpha(Palette.HORIZON, 70),
                        Palette.alpha(Palette.HORIZON, 0)},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, horizon - w * 0.9f, w, horizon + h * 0.03f, fill);
        fill.setShader(null);

        // --- retro sun -------------------------------------------------------
        float sunR = w * 0.20f;
        float sunCx = persp.centreX;
        float sunCy = horizon - sunR * 0.42f;
        c.saveLayer(0, 0, w, ih, null);
        fill.setShader(new LinearGradient(0, sunCy - sunR, 0, sunCy + sunR,
                Palette.SUN_TOP, Palette.SUN_BOTTOM, Shader.TileMode.CLAMP));
        c.drawCircle(sunCx, sunCy, sunR, fill);
        fill.setShader(null);
        Paint clear = new Paint();
        clear.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.CLEAR));
        float band = sunR * 0.075f;
        for (int i = 0; i < 8; i++) {
            float y = sunCy - sunR * 0.02f + i * band * 1.45f;
            float gap = band * (0.30f + i * 0.26f);
            c.drawRect(sunCx - sunR, y, sunCx + sunR, y + gap, clear);
        }
        c.restore();

        // --- mountains -------------------------------------------------------
        drawRidge(c, horizon, w * 0.155f, 0xFF2A0B44, 8, 909L);
        drawRidge(c, horizon, w * 0.095f, 0xFF1B0730, 6, 313L);

        background = sky;

        // The ground below the horizon is a cheap cached gradient drawn every
        // frame instead of a full-screen bitmap (saves ~7 MB of heap).
        groundShader = new LinearGradient(0, horizon, 0, h,
                new int[]{0xFF1B0736, 0xFF0C0420, 0xFF050210},
                new float[]{0f, 0.38f, 1f}, Shader.TileMode.CLAMP);
        roadShader = new LinearGradient(0, horizon, 0, h,
                new int[]{0xFF200E3C, 0xFF170B2C, 0xFF0D0620},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
    }

    private void drawRidge(Canvas c, float horizon, float amplitude, int color, int peaks,
                           long seed) {
        Random rnd = new Random(seed);
        path2.reset();
        path2.moveTo(-w * 0.05f, horizon + 1f);
        int n = peaks * 2;
        for (int i = 0; i <= n; i++) {
            float x = -w * 0.05f + (w * 1.1f) * i / (float) n;
            float y = horizon - (i % 2 == 0 ? amplitude * (0.35f + rnd.nextFloat() * 0.65f)
                    : amplitude * 0.12f);
            path2.lineTo(x, y);
        }
        path2.lineTo(w * 1.05f, horizon + 1f);
        path2.close();
        fill.setShader(new LinearGradient(0, horizon - amplitude, 0, horizon,
                color, Palette.alpha(color, 255), Shader.TileMode.CLAMP));
        c.drawPath(path2, fill);
        fill.setShader(null);
    }

    private void buildSkyline() {
        int iw = (int) (w * 2f);
        int ih = (int) (h * 0.16f);
        // no recycle(): the previous bitmap is simply dropped and collected,
        // so a frame still holding it can never draw a recycled bitmap
        Bitmap fresh;
        try {
            fresh = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError oom) {
            skyline = null;
            return;
        }
        skyline = fresh;
        Canvas c = new Canvas(fresh);
        Random rnd = new Random(777L);
        float baseY = ih;
        float x = 0f;
        while (x < iw) {
            float bw = (0.018f + rnd.nextFloat() * 0.030f) * iw;
            float bh = (0.22f + rnd.nextFloat() * 0.66f) * ih;
            int col = rnd.nextFloat() < 0.5f ? Palette.CITY_WINDOW_A : Palette.CITY_WINDOW_B;
            // draw twice so the strip tiles seamlessly
            drawBuilding(c, x, baseY - bh, bw, bh, col, rnd);
            if (x + bw > iw) {
                drawBuilding(c, x - iw, baseY - bh, bw, bh, col, rnd);
            }
            x += bw + 1.5f * unit;
        }
        // ground line
        fill.setColor(Palette.alpha(Palette.HORIZON, 120));
        c.drawRect(0, baseY - 2f, iw, baseY, fill);
    }

    private void drawBuilding(Canvas c, float x, float y, float bw, float bh, int windowColor,
                              Random rnd) {
        fill.setColor(Palette.CITY);
        c.drawRect(x, y, x + bw, y + bh, fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.2f * unit);
        stroke.setColor(Palette.alpha(windowColor, 90));
        c.drawRect(x, y, x + bw, y + bh, stroke);
        int cols = Math.max(1, (int) (bw / (7f * unit)));
        int rows = Math.max(1, (int) (bh / (11f * unit)));
        float cw = bw / cols;
        float chh = bh / rows;
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < rows; j++) {
                if (rnd.nextFloat() > 0.42f) {
                    continue;
                }
                fill.setColor(Palette.alpha(windowColor,
                        55 + rnd.nextInt(150)));
                c.drawRect(x + i * cw + cw * 0.28f, y + j * chh + chh * 0.28f,
                        x + i * cw + cw * 0.72f, y + j * chh + chh * 0.66f, fill);
            }
        }
        // antenna
        if (rnd.nextFloat() < 0.3f) {
            float ax = x + bw * (0.3f + rnd.nextFloat() * 0.4f);
            fill.setColor(Palette.alpha(windowColor, 110));
            c.drawRect(ax, y - bh * 0.22f, ax + 1.4f * unit, y, fill);
        }
    }

    private void buildScanline() {
        // power-of-two tile: TileMode.REPEAT on the GPU wants POT dimensions
        int sw = 8;
        int sh = 8;
        Bitmap tile = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(tile);
        Paint pp = new Paint();
        pp.setColor(Palette.alpha(0xFFFFFFFF, 26));
        c.drawRect(0, 0, sw, 1.5f, pp);
        pp.setColor(Palette.alpha(0xFF000000, 22));
        c.drawRect(0, 1.5f, sw, sh, pp);
        scanShader = new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        vignette = new RadialGradient(persp.centreX, h * 0.5f, Math.max(w, h) * 0.78f,
                new int[]{Palette.alpha(0xFF000000, 0), Palette.alpha(0xFF000000, 0),
                        Palette.alpha(0xFF000000, 150)},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
    }

    // -------------------------------------------------------------------- draw

    public void draw(Canvas c, Engine engine, int mode, float anim, float dt,
                     boolean soundOn, float deathProgress) {
        draw(c, engine, mode, anim, dt, soundOn, musicOnState, deathProgress);
    }

    public void draw(Canvas c, Engine engine, int mode, float anim, float dt,
                     boolean soundOn, boolean musicOn, float deathProgress) {
        musicOnState = musicOn;
        // A Paint keeps its alpha/colour/shader between draws: reset what we
        // share every frame, otherwise a colour set by the previous frame (or
        // by the HUD) leaks into the next one and draws layers semi-transparent.
        fill.setAlpha(255);
        fill.setColor(Palette.WHITE);
        fill.setShader(null);
        stroke.setAlpha(255);
        stroke.setShader(null);
        applyPendingSize();
        if (w <= 0f || h <= 0f) {
            c.drawColor(Palette.BG_TOP);
            return;
        }
        updateParticles(dt);
        soundOnState = soundOn;
        c.drawColor(Palette.BG_TOP);

        c.save();
        if (shake > 0.01f) {
            c.translate((fX.nextFloat() - 0.5f) * shake, (fX.nextFloat() - 0.5f) * shake);
            shake *= (float) Math.pow(0.0025f, dt);
        }

        drawSky(c, engine, anim);
        drawRoad(c, engine, anim);
        drawEntities(c, engine);
        drawPlayer(c, engine, anim);
        drawParticles(c);
        c.restore();

        drawHud(c, engine, mode, anim, soundOn);

        if (mode == MODE_MENU) {
            drawMenu(c, engine, anim, deathProgress);
        } else if (mode == MODE_PAUSED) {
            drawPaused(c, engine, anim);
        } else if (mode == MODE_OVER) {
            drawGameOver(c, engine, anim, deathProgress);
        }

        drawOverlay(c);

        if (flash > 0.01f) {
            fill.setColor(Palette.alpha(Palette.WHITE, (int) (flash * 190)));
            fill.setShader(null);
            c.drawRect(0, 0, w, h, fill);
            flash *= (float) Math.pow(0.0009f, dt);
        }
    }

    private void drawSky(Canvas c, Engine engine, float anim) {
        if (background != null) {
            c.drawBitmap(background, 0, 0, null);
        } else {
            // fallback when the sky bitmap could not be allocated
            fill.setShader(skyFallback);
            c.drawRect(0, 0, w, persp.horizonY, fill);
            fill.setShader(null);
        }
        // ground below the horizon (cached gradient, one rect per frame)
        fill.setAlpha(255);
        fill.setShader(groundShader);
        c.drawRect(0, persp.horizonY, w, h, fill);
        fill.setShader(null);
        // parallax skyline
        if (skyline != null) {
            float sw = skyline.getWidth();
            float offset = -((engine.travel * 0.55f) % sw);
            float top = persp.horizonY - skyline.getHeight() + 2f * unit;
            c.drawBitmap(skyline, offset, top, null);
            c.drawBitmap(skyline, offset + sw, top, null);
        }
        // pulsing haze on the horizon
        float pulse = 0.55f + 0.45f * (float) Math.sin(anim * 1.7f);
        fill.setShader(new RadialGradient(persp.centreX, persp.horizonY, w * 0.52f,
                new int[]{Palette.alpha(Palette.HORIZON, (int) (46 * pulse)),
                        Palette.alpha(Palette.HORIZON, 0)},
                null, Shader.TileMode.CLAMP));
        // clipped to the sky: drawing the lower half of this radial glow over
        // the ground left a visible magenta arc just below the horizon
        c.drawRect(persp.centreX - w * 0.55f, persp.horizonY - w * 0.52f,
                persp.centreX + w * 0.55f, persp.horizonY, fill);
        fill.setShader(null);
    }

    private void drawRoad(Canvas c, Engine engine, float anim) {
        float nearD = 0.4f;
        float farD = Engine.FAR_D;
        float horizon = persp.horizonY;

        float scroll = engine.travel % GRID_STEP;

        // ---- floor grid: horizontal lines across the whole width -----------
        stroke.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 34; i++) {
            float d = nearD + i * GRID_STEP - scroll + GRID_STEP;
            if (d < nearD || d > farD) {
                continue;
            }
            float y = persp.screenY(d);
            float fade = clamp01(1f - d / farD);
            float sweep = 0.6f + 0.4f * (float) Math.sin(anim * 2.6f - d * 0.22f);
            int a = (int) (60f * fade * fade * sweep);
            if (a < 4) {
                continue;
            }
            stroke.setColor(Palette.alpha(Palette.EDGE_RIGHT, a));
            stroke.setStrokeWidth((1f + 2.2f * persp.scale(d)) * unit);
            c.drawLine(0, y, w, y, stroke);
        }

        // ---- floor grid: lines running towards the vanishing point ---------
        float yn = persp.screenY(nearD);
        float yf = persp.screenY(farD);
        for (int i = 0; i < GROUND_LINES.length; i++) {
            float lat = GROUND_LINES[i];
            stroke.setColor(Palette.alpha(Palette.EDGE_LEFT, 34));
            stroke.setStrokeWidth(1.2f * unit);
            c.drawLine(persp.screenX(lat, nearD), yn, persp.screenX(lat, farD), yf, stroke);
        }

        // ---- track surface --------------------------------------------------
        path.reset();
        path.moveTo(persp.screenX(-1f, nearD), yn);
        path.lineTo(persp.screenX(-1f, farD), yf);
        path.lineTo(persp.screenX(1f, farD), yf);
        path.lineTo(persp.screenX(1f, nearD), yn);
        path.close();
        fill.setAlpha(255);
        fill.setShader(roadShader);
        c.drawPath(path, fill);
        fill.setAlpha(255);
        fill.setShader(null);

        // ---- track grid (brighter than the surrounding floor) ---------------
        for (int i = 0; i < 34; i++) {
            float d = nearD + i * GRID_STEP - scroll + GRID_STEP;
            if (d < nearD || d > farD) {
                continue;
            }
            float y = persp.screenY(d);
            float fade = clamp01(1f - d / farD);
            float sweep = 0.55f + 0.45f * (float) Math.sin(anim * 3.0f - d * 0.16f);
            int a = (int) (150f * fade * fade * sweep);
            if (a < 6) {
                continue;
            }
            stroke.setColor(Palette.alpha(Palette.ROAD_GRID, a));
            stroke.setStrokeWidth((1.1f + 2.8f * persp.scale(d)) * unit);
            c.drawLine(persp.screenX(-1f, d), y, persp.screenX(1f, d), y, stroke);
        }

        // ---- lane separators (dashed) ---------------------------------------
        for (int lane = 1; lane <= 2; lane++) {
            float lx = -1f + lane * (2f / 3f);
            for (int i = 0; i < 40; i++) {
                float d0 = 1f + i * GRID_STEP * 0.5f - (engine.travel % (GRID_STEP * 0.5f));
                float d1 = d0 + GRID_STEP * 0.26f;
                if (d1 < nearD || d0 > farD) {
                    continue;
                }
                d0 = Math.max(nearD, d0);
                float fade = clamp01(1f - d0 / farD);
                stroke.setColor(Palette.alpha(Palette.ROAD_GRID, (int) (70 * fade)));
                stroke.setStrokeWidth((1f + 2.4f * persp.scale(d0)) * unit);
                c.drawLine(persp.screenX(lx, d0), persp.screenY(d0),
                        persp.screenX(lx, d1), persp.screenY(d1), stroke);
            }
        }

        // ---- glowing edges ---------------------------------------------------
        drawEdge(c, -1f, Palette.EDGE_LEFT, farD, nearD);
        drawEdge(c, 1f, Palette.EDGE_RIGHT, farD, nearD);

        // ---- pylons ----------------------------------------------------------
        for (int i = 0; i < 26; i++) {
            float d = 2f + i * 11f - (engine.travel % 11f);
            if (d < 1.5f || d > farD * 0.92f) {
                continue;
            }
            float sc = persp.scale(d);
            float fade = clamp01(1f - d / (farD * 0.92f));
            float xL = persp.screenX(-1.045f, d);
            float xR = persp.screenX(1.045f, d);
            float y = persp.screenY(d);
            float ph = 30f * unit * sc;
            stroke.setStrokeWidth((1.3f + 2.6f * sc) * unit);
            stroke.setColor(Palette.alpha(Palette.EDGE_LEFT, (int) (190 * fade)));
            c.drawLine(xL, y, xL, y - ph, stroke);
            stroke.setColor(Palette.alpha(Palette.EDGE_RIGHT, (int) (190 * fade)));
            c.drawLine(xR, y, xR, y - ph, stroke);
            fill.setColor(Palette.alpha(Palette.WHITE, (int) (170 * fade)));
            c.drawCircle(xL, y - ph, 1.6f * unit * sc + 1.6f * unit, fill);
            c.drawCircle(xR, y - ph, 1.6f * unit * sc + 1.6f * unit, fill);
        }
    }

    private void drawEdge(Canvas c, float lateral, int color, float farD, float nearD) {
        float x0 = persp.screenX(lateral, nearD);
        float y0 = persp.screenY(nearD);
        float x1 = persp.screenX(lateral, farD);
        float y1 = persp.screenY(farD);
        stroke.setStyle(Paint.Style.STROKE);
        // wide soft glow
        stroke.setShader(new LinearGradient(x0, y0, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f,
                Palette.alpha(color, 120), Palette.alpha(color, 0), Shader.TileMode.CLAMP));
        stroke.setStrokeWidth(26f * unit);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x0, y0, x1, y1, stroke);
        stroke.setShader(null);
        stroke.setColor(color);
        stroke.setStrokeWidth(3.4f * unit);
        c.drawLine(x0, y0, x1, y1, stroke);
        stroke.setColor(Palette.alpha(Palette.WHITE, 210));
        stroke.setStrokeWidth(1.3f * unit);
        c.drawLine(x0, y0, x1, y1, stroke);
    }

    // ---------------------------------------------------------------- entities

    private final int[] order = new int[128];

    private void drawEntities(Canvas c, Engine engine) {
        int n = engine.entities.size();
        int count = Math.min(n, order.length);
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        // insertion sort, far to near
        for (int i = 1; i < count; i++) {
            int key = order[i];
            float kd = engine.entities.get(key).d;
            int j = i - 1;
            while (j >= 0 && engine.entities.get(order[j]).d < kd) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = key;
        }
        for (int i = 0; i < count; i++) {
            Entity e = engine.entities.get(order[i]);
            if (e.d < 0.6f) {
                continue;
            }
            if (e.type == Entity.TYPE_ORB) {
                drawOrb(c, e);
            } else {
                drawObstacle(c, e);
            }
        }
    }

    private void drawOrb(Canvas c, Entity e) {
        float sc = persp.scale(e.d);
        float sx = persp.screenX(e.x, e.d);
        float sy = persp.screenY(e.d) - (14f + 6f * (float) Math.sin(e.phase * 1.4f)) * unit
                * Math.min(1.8f, sc);
        float r = 13f * unit * sc;
        float fade = clamp01(1.35f - e.d / (Engine.FAR_D * 0.85f));
        int a = (int) (255 * fade);
        if (a < 8) {
            return;
        }
        // halo
        fill.setShader(new RadialGradient(sx, sy, r * 4.2f,
                new int[]{Palette.alpha(Palette.ORB_GLOW, (int) (170 * fade)),
                        Palette.alpha(Palette.ORB_GLOW, (int) (60 * fade)),
                        Palette.alpha(Palette.ORB_GLOW, 0)},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(sx, sy, r * 4.2f, fill);
        fill.setShader(null);

        // rotating ring
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth((1.4f + 1.8f * sc) * unit);
        stroke.setColor(Palette.alpha(Palette.ORB_CORE, (int) (140 * fade)));
        c.drawCircle(sx, sy, r * (1.55f + 0.12f * (float) Math.sin(e.phase * 2.4f)), stroke);

        // bright core
        fill.setShader(new RadialGradient(sx - r * 0.25f, sy - r * 0.25f, r * 1.1f,
                new int[]{Palette.WHITE, Palette.ORB_CORE, Palette.ORB_GLOW},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(sx, sy, r * 0.92f, fill);
        fill.setShader(null);
        fill.setColor(Palette.alpha(Palette.WHITE, (int) (230 * fade)));
        c.drawCircle(sx - r * 0.22f, sy - r * 0.24f, r * 0.34f, fill);
    }

    private void drawObstacle(Canvas c, Entity e) {
        float sc = persp.scale(e.d);
        float sx = persp.screenX(e.x, e.d);
        float sy = persp.screenY(e.d);
        float halfW = Engine.OBSTACLE_HALF_W * persp.halfWidthPx(e.d);
        float fade = clamp01(1.32f - e.d / (Engine.FAR_D * 0.92f));
        int a = (int) (255 * fade);
        if (a < 8) {
            return;
        }

        int base;
        float heightF;
        switch (e.variant) {
            case Entity.VARIANT_PILLAR:
                base = Palette.DANGER_B;
                heightF = 2.9f;
                halfW *= 0.6f;
                break;
            case Entity.VARIANT_GATE:
                base = Palette.DANGER_C;
                heightF = 2.1f;
                break;
            default:
                base = Palette.DANGER_A;
                heightF = 1.5f;
                break;
        }
        float top = sy - heightF * halfW * 2f;
        float midY = (top + sy) * 0.5f;

        // ---- neon halo around the whole obstacle ---------------------------
        float haloR = Math.max(halfW * 3.2f, 3f);
        fill.setShader(new RadialGradient(sx, midY, haloR,
                new int[]{Palette.alpha(base, (int) (105 * fade)),
                        Palette.alpha(base, (int) (34 * fade)),
                        Palette.alpha(base, 0)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(sx, midY, haloR, fill);
        fill.setShader(null);

        // ---- 3D roof -------------------------------------------------------
        float dBack = e.d + Math.max(1.6f, 2.4f * sc);
        float scBack = persp.scale(dBack);
        float sxBack = persp.screenX(e.x, dBack);
        float halfWBack = halfW * (scBack / sc);
        float syBack = persp.screenY(dBack);
        float topBack = syBack - heightF * halfWBack * 2f;

        path.reset();
        path.moveTo(sx - halfW, top);
        path.lineTo(sx + halfW, top);
        path.lineTo(sxBack + halfWBack, topBack);
        path.lineTo(sxBack - halfWBack, topBack);
        path.close();
        fill.setShader(new LinearGradient(sx, topBack, sx, top,
                Palette.alpha(Palette.mix(base, 0xFF05010F, 0.82f), a),
                Palette.alpha(Palette.mix(base, 0xFF05010F, 0.55f), a),
                Shader.TileMode.CLAMP));
        c.drawPath(path, fill);
        fill.setShader(null);

        // ---- front face: dark panel, the neon comes from the edges ---------
        rect.set(sx - halfW, top, sx + halfW, sy);
        fill.setColor(Palette.alpha(0xFF0C0518, 205));
        c.drawRect(rect, fill);
        fill.setShader(new LinearGradient(sx, top, sx, sy,
                Palette.alpha(base, (int) (58 * fade)),
                Palette.alpha(base, 0),
                Shader.TileMode.CLAMP));
        c.drawRect(rect, fill);
        fill.setShader(null);

        // ---- inner detail ---------------------------------------------------
        stroke.setStyle(Paint.Style.STROKE);
        if (e.variant == Entity.VARIANT_BLOCK) {
            // stacked energy bars
            float barH = (sy - top) * 0.10f;
            for (int i = 0; i < 3; i++) {
                float y = top + (sy - top) * (0.22f + i * 0.24f);
                fill.setColor(Palette.alpha(base, (int) (120 * fade)));
                c.drawRect(sx - halfW * 0.62f, y, sx + halfW * 0.62f, y + barH, fill);
                fill.setColor(Palette.alpha(Palette.WHITE, (int) (90 * fade)));
                c.drawRect(sx - halfW * 0.62f, y, sx + halfW * 0.62f, y + barH * 0.28f, fill);
            }
        } else if (e.variant == Entity.VARIANT_GATE) {
            // sweeping energy scanline
            float y = top + (sy - top) * (0.5f + 0.36f * (float) Math.sin(e.phase * 1.9f));
            fill.setShader(new LinearGradient(sx - halfW, y, sx + halfW, y,
                    new int[]{Palette.alpha(base, 0), Palette.alpha(base, (int) (220 * fade)),
                            Palette.alpha(base, 0)},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(sx - halfW, y - 3f * unit, sx + halfW, y + 3f * unit, fill);
            fill.setShader(null);
            fill.setColor(Palette.alpha(Palette.WHITE, (int) (200 * fade)));
            c.drawRect(sx - halfW, y - 1f * unit, sx + halfW, y + 1f * unit, fill);
        } else {
            // pillar: bright vertical reactor core
            fill.setShader(new LinearGradient(sx, top, sx, sy,
                    Palette.alpha(Palette.WHITE, (int) (215 * fade)),
                    Palette.alpha(base, (int) (90 * fade)), Shader.TileMode.CLAMP));
            c.drawRect(sx - halfW * 0.20f, top + halfW * 0.35f, sx + halfW * 0.20f, sy, fill);
            fill.setShader(null);
        }

        // ---- neon frame: soft glow, bright edge, white core ------------------
        float wf = Math.min(1.7f, Math.max(0.35f, sc));
        stroke.setStrokeWidth(13f * unit * wf);
        stroke.setColor(Palette.alpha(base, (int) (80 * fade)));
        c.drawRect(rect, stroke);
        stroke.setStrokeWidth(4.6f * unit * wf);
        stroke.setColor(Palette.alpha(base, a));
        c.drawRect(rect, stroke);
        stroke.setStrokeWidth(5.6f * unit * wf);
        stroke.setColor(Palette.alpha(base, (int) (60 * fade)));
        c.drawRect(rect, stroke);
        stroke.setStrokeWidth(1.6f * unit * wf);
        stroke.setColor(Palette.alpha(Palette.WHITE, (int) (235 * fade)));
        c.drawRect(rect, stroke);
    }

    // ------------------------------------------------------------------ player

    private void drawPlayer(Canvas c, Engine engine, float anim) {
        float sx = persp.screenX(engine.playerX, Engine.PLAYER_D);
        float sy = persp.screenY(Engine.PLAYER_D);
        float s = w * 0.132f;

        // ground shadow
        fill.setShader(new RadialGradient(sx, sy + s * 0.26f, s * 0.60f,
                new int[]{Palette.alpha(Palette.EDGE_LEFT, 90), Palette.alpha(0xFF000000, 0)},
                null, Shader.TileMode.CLAMP));
        c.drawCircle(sx, sy + s * 0.26f, s * 0.60f, fill);
        fill.setShader(null);

        c.save();
        c.translate(sx, sy + 2f * unit);
        float bank = Math.max(-1f, Math.min(1f, engine.playerVX / 3.1f));
        c.rotate(bank * 9f);
        c.scale(s, s * 1.05f);

        ship.reset();
        ship.moveTo(0f, -0.62f);
        ship.lineTo(0.13f, -0.30f);
        ship.lineTo(0.17f, 0.02f);
        ship.lineTo(0.50f, 0.20f);
        ship.lineTo(0.44f, 0.34f);
        ship.lineTo(0.16f, 0.30f);
        ship.lineTo(0.10f, 0.44f);
        ship.lineTo(-0.10f, 0.44f);
        ship.lineTo(-0.16f, 0.30f);
        ship.lineTo(-0.44f, 0.34f);
        ship.lineTo(-0.50f, 0.20f);
        ship.lineTo(-0.17f, 0.02f);
        ship.lineTo(-0.13f, -0.30f);
        ship.close();

        // thruster flames
        float flick = 0.85f + 0.15f * (float) Math.sin(anim * 42f);
        for (int i = 0; i < 2; i++) {
            float ox = i == 0 ? -0.16f : 0.16f;
            path.reset();
            path.moveTo(ox - 0.075f, 0.40f);
            path.lineTo(ox + 0.075f, 0.40f);
            path.lineTo(ox, 0.40f + 0.44f * flick * (0.8f + fX.nextFloat() * 0.4f));
            path.close();
            fill.setShader(new LinearGradient(0f, 0.40f, 0f, 0.86f,
                    Palette.alpha(Palette.WHITE, 245), Palette.alpha(Palette.THRUSTER, 30),
                    Shader.TileMode.CLAMP));
            c.drawPath(path, fill);
        }
        fill.setShader(null);

        // glow under the hull
        fill.setShader(new LinearGradient(0f, -0.6f, 0f, 0.45f,
                Palette.alpha(Palette.SHIP_ACCENT, 46), Palette.alpha(Palette.EDGE_LEFT, 12),
                Shader.TileMode.CLAMP));
        c.drawPath(ship, fill);
        fill.setShader(null);

        // hull
        fill.setShader(new LinearGradient(0f, -0.62f, 0f, 0.44f,
                Palette.SHIP_BODY_TOP, Palette.SHIP_BODY_BOTTOM, Shader.TileMode.CLAMP));
        c.drawPath(ship, fill);
        fill.setShader(null);

        // neon outline + accent
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(0.055f);
        stroke.setColor(Palette.alpha(Palette.SHIP_ACCENT, 230));
        c.drawPath(ship, stroke);
        stroke.setStrokeWidth(0.016f);
        stroke.setColor(Palette.WHITE);
        c.drawPath(ship, stroke);

        // cockpit
        fill.setShader(new LinearGradient(0f, -0.42f, 0f, 0.02f,
                0xFF35E6FF, 0xFF0B2A6B, Shader.TileMode.CLAMP));
        path.reset();
        path.moveTo(0f, -0.44f);
        path.lineTo(0.085f, -0.06f);
        path.lineTo(0f, 0.02f);
        path.lineTo(-0.085f, -0.06f);
        path.close();
        c.drawPath(path, fill);
        fill.setShader(null);

        // wing accents
        stroke.setStrokeWidth(0.035f);
        stroke.setColor(Palette.alpha(Palette.EDGE_LEFT, 220));
        c.drawLine(-0.40f, 0.26f, -0.20f, 0.10f, stroke);
        c.drawLine(0.40f, 0.26f, 0.20f, 0.10f, stroke);

        c.restore();

        // engine sparks
        if (fX.nextFloat() < 0.38f) {
            spawn(sx + (fX.nextFloat() - 0.5f) * s * 0.28f, sy + s * 0.46f,
                    (fX.nextFloat() - 0.5f) * 60f * unit, (140f + fX.nextFloat() * 160f) * unit,
                    0.30f, (2f + fX.nextFloat() * 3.5f) * unit,
                    fX.nextBoolean() ? Palette.THRUSTER : Palette.EDGE_LEFT, 0.94f, true);
        }
    }

    // --------------------------------------------------------------- particles

    private void spawn(float x, float y, float vx, float vy, float life, float size, int color,
                       float drag, boolean screenSpace) {
        if (particleCount >= MAX_PARTICLES) {
            return;
        }
        int i = particleCount++;
        pX[i] = x;
        pY[i] = y;
        pVX[i] = vx;
        pVY[i] = vy;
        pLife[i] = life;
        pMax[i] = life;
        pSize[i] = size;
        pColor[i] = color;
        pDrag[i] = drag;
        pScreen[i] = screenSpace;
    }

    private void updateParticles(float dt) {
        for (int i = particleCount - 1; i >= 0; i--) {
            pLife[i] -= dt;
            if (pLife[i] <= 0f) {
                removeParticle(i);
                continue;
            }
            pX[i] += pVX[i] * dt;
            pY[i] += pVY[i] * dt;
            float damp = (float) Math.pow(pDrag[i], dt);
            pVX[i] *= damp;
            pVY[i] *= damp;
        }
    }

    private void removeParticle(int i) {
        int last = particleCount - 1;
        pX[i] = pX[last];
        pY[i] = pY[last];
        pVX[i] = pVX[last];
        pVY[i] = pVY[last];
        pLife[i] = pLife[last];
        pMax[i] = pMax[last];
        pSize[i] = pSize[last];
        pColor[i] = pColor[last];
        pDrag[i] = pDrag[last];
        pScreen[i] = pScreen[last];
        particleCount--;
    }

    private void drawParticles(Canvas c) {
        for (int i = 0; i < particleCount; i++) {
            float k = pLife[i] / pMax[i];
            int a = (int) (255 * Math.min(1f, k * 1.6f));
            fill.setColor(Palette.alpha(pColor[i], a));
            fill.setShader(null);
            c.drawCircle(pX[i], pY[i], pSize[i] * (0.35f + 0.65f * k), fill);
        }
    }

    /** Spawns the visual feedback for one gameplay event. */
    public void handleEvents(Engine engine) {
        Events ev = engine.events;
        for (int i = 0; i < ev.size(); i++) {
            float x = persp.screenX(ev.x(i), Math.max(1.2f, ev.d(i)));
            float y = persp.screenY(Math.max(1.2f, ev.d(i)));
            switch (ev.type(i)) {
                case Events.ORB_PICKED:
                    burst(x, y, 11, Palette.ORB_GLOW, 260f, 0.42f);
                    flash = Math.max(flash, 0.10f);
                    break;
                case Events.NEAR_MISS:
                    burst(x, y, 6, Palette.SHIP_ACCENT, 200f, 0.32f);
                    break;
                case Events.CRASH:
                    crashBurst(ev.x(i));
                    shake = 46f * unit;
                    flash = 1f;
                    deathFx = 0f;
                    break;
                case Events.NEW_RECORD:
                    flash = Math.max(flash, 0.22f);
                    break;
                default:
                    break;
            }
        }
    }

    private void burst(float x, float y, int n, int color, float speed, float life) {
        for (int i = 0; i < n; i++) {
            double ang = fX.nextFloat() * Math.PI * 2;
            float v = speed * (0.35f + fX.nextFloat()) * unit;
            spawn(x, y, (float) Math.cos(ang) * v, (float) Math.sin(ang) * v * 0.7f,
                    life * (0.6f + fX.nextFloat() * 0.7f),
                    (2.5f + fX.nextFloat() * 5f) * unit, color, 0.06f, true);
        }
    }

    private void crashBurst(float lateral) {
        float sx = persp.screenX(lateral, Engine.PLAYER_D);
        float sy = persp.screenY(Engine.PLAYER_D);
        for (int i = 0; i < 70; i++) {
            double ang = fX.nextFloat() * Math.PI * 2;
            float v = (120f + fX.nextFloat() * 620f) * unit;
            int col = i % 3 == 0 ? Palette.DANGER_C
                    : (i % 3 == 1 ? Palette.DANGER_A : Palette.GOLD);
            spawn(sx, sy, (float) Math.cos(ang) * v, (float) Math.sin(ang) * v * 0.8f,
                    0.5f + fX.nextFloat() * 0.9f, (3f + fX.nextFloat() * 9f) * unit,
                    col, 0.10f, true);
        }
        for (int i = 0; i < 26; i++) {
            spawn(sx + (fX.nextFloat() - 0.5f) * 60f * unit, sy,
                    (fX.nextFloat() - 0.5f) * 120f * unit,
                    -(60f + fX.nextFloat() * 260f) * unit,
                    0.8f + fX.nextFloat() * 0.8f, (6f + fX.nextFloat() * 12f) * unit,
                    Palette.alpha(0xFF444466, 200), 0.4f, true);
        }
    }

    public void clearParticles() {
        particleCount = 0;
        shake = 0f;
        flash = 0f;
    }

    // --------------------------------------------------------------------- HUD

    private void drawHud(Canvas c, Engine engine, int mode, float anim, boolean soundOn) {
        if (mode == MODE_MENU) {
            return;
        }
        String score = String.valueOf(engine.score());
        label(c, "SCORE", 34f * unit, 62f * unit, 24f * unit, Palette.TEXT_DIM, 2.2f,
                Paint.Align.LEFT);
        glowText(c, score, 34f * unit, 140f * unit, 84f * unit, Palette.TEXT,
                Palette.EDGE_RIGHT, Paint.Align.LEFT, 1.5f);

        label(c, "RECORD " + Math.max(engine.best, engine.score()), 34f * unit, 182f * unit,
                24f * unit, Palette.alpha(Palette.GOLD, 220), 1.4f, Paint.Align.LEFT);

        // combo
        if (engine.combo > 0) {
            float pop = 1f;
            if (engine.combo != lastCombo) {
                comboPop = 1f;
                lastCombo = engine.combo;
            }
            comboPop = Math.max(0f, comboPop - 0.05f);
            pop = 1f + 0.22f * comboPop;
            c.save();
            c.translate(w - 150f * unit, 150f * unit);
            c.scale(pop, pop);
            glowText(c, "x" + engine.multiplier(), 0f, 0f, 62f * unit, Palette.GOLD,
                    Palette.DANGER_C, Paint.Align.CENTER, 0.5f);
            label(c, "COMBO", 0f, 30f * unit, 20f * unit, Palette.alpha(Palette.GOLD, 200), 2f);
            c.restore();
        }

        // orbs
        fill.setShader(null);
        fill.setColor(Palette.alpha(Palette.ORB_GLOW, 200));
        c.drawCircle(46f * unit, h - 62f * unit, 10f * unit, fill);
        fill.setColor(Palette.ORB_CORE);
        c.drawCircle(46f * unit, h - 62f * unit, 5f * unit, fill);
        glowText(c, "x" + engine.orbs, 68f * unit, h - 50f * unit, 34f * unit, Palette.TEXT,
                Palette.ORB_GLOW, Paint.Align.LEFT, 0.5f);

        // speed gauge
        float t = clamp01((engine.speed - Engine.MIN_SPEED)
                / (Engine.MAX_SPEED - Engine.MIN_SPEED));
        float gx = w - 60f * unit;
        float gy0 = h - 90f * unit;
        float gy1 = h - 62f * unit - 250f * unit * t;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(9f * unit);
        stroke.setColor(Palette.alpha(Palette.TEXT, 40));
        c.drawLine(gx, h - 320f * unit, gx, h - 62f * unit, stroke);
        stroke.setShader(new LinearGradient(gx, h - 62f * unit, gx, h - 320f * unit,
                Palette.EDGE_RIGHT, Palette.EDGE_LEFT, Shader.TileMode.CLAMP));
        stroke.setStrokeWidth(9f * unit);
        c.drawLine(gx, gy0, gx, Math.min(gy0, gy1), stroke);
        stroke.setShader(null);
        label(c, (int) (engine.speed * 7.6f) + " KM/H", gx + 4f * unit, h - 52f * unit,
                21f * unit, Palette.TEXT_DIM, 0.6f, Paint.Align.RIGHT);

        // buttons
        if (mode == MODE_PLAY) {
            drawIconButton(c, btnPause, ICON_PAUSE, true);
            drawIconButton(c, btnSound, ICON_SPEAKER, soundOn);
        }
    }

    public static final int ICON_PAUSE = 0;
    public static final int ICON_SPEAKER = 1;
    public static final int ICON_MUSIC = 2;

    private void drawIconButton(Canvas c, RectF r, int icon, boolean on) {
        rect2.set(r);
        fill.setColor(Palette.alpha(0xFF170A2E, 150));
        c.drawRoundRect(rect2, r.height() * 0.42f, r.height() * 0.42f, fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.6f * unit);
        stroke.setColor(Palette.alpha(Palette.EDGE_RIGHT, 120));
        c.drawRoundRect(rect2, r.height() * 0.42f, r.height() * 0.42f, stroke);
        float cx = r.centerX();
        float cy = r.centerY();
        float s = r.height() * 0.26f;
        if (icon == ICON_MUSIC) {
            // music note
            fill.setColor(Palette.alpha(on ? Palette.TEXT : Palette.TEXT_DIM, 235));
            c.drawCircle(cx - s * 0.55f, cy + s * 0.55f, s * 0.52f, fill);
            c.drawRect(cx - s * 0.18f, cy - s * 1.05f, cx + s * 0.02f, cy + s * 0.6f, fill);
            path.reset();
            path.moveTo(cx + s * 0.02f, cy - s * 1.05f);
            path.lineTo(cx + s * 0.85f, cy - s * 0.72f);
            path.lineTo(cx + s * 0.85f, cy - s * 0.32f);
            path.lineTo(cx + s * 0.02f, cy - s * 0.66f);
            path.close();
            c.drawPath(path, fill);
            if (!on) {
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(1.8f * unit);
                stroke.setColor(Palette.alpha(Palette.TEXT_DIM, 220));
                c.drawLine(cx - s * 1.1f, cy - s * 1.1f, cx + s * 1.1f, cy + s * 1.1f, stroke);
            }
        } else if (icon == ICON_PAUSE) {
            fill.setColor(Palette.alpha(Palette.TEXT, 230));
            c.drawRect(cx - s * 0.75f, cy - s, cx - s * 0.15f, cy + s, fill);
            c.drawRect(cx + s * 0.15f, cy - s, cx + s * 0.75f, cy + s, fill);
        } else {
            // speaker
            path.reset();
            path.moveTo(cx - s * 1.1f, cy - s * 0.45f);
            path.lineTo(cx - s * 0.35f, cy - s * 0.45f);
            path.lineTo(cx + s * 0.25f, cy - s);
            path.lineTo(cx + s * 0.25f, cy + s);
            path.lineTo(cx - s * 0.35f, cy + s * 0.45f);
            path.lineTo(cx - s * 1.1f, cy + s * 0.45f);
            path.close();
            fill.setColor(Palette.alpha(on ? Palette.TEXT : Palette.TEXT_DIM, 230));
            c.drawPath(path, fill);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.8f * unit);
            stroke.setColor(Palette.alpha(on ? Palette.EDGE_RIGHT : Palette.TEXT_DIM, 220));
            if (on) {
                c.drawArc(cx - s * 1.2f, cy - s * 1.2f, cx + s * 1.8f, cy + s * 1.2f, -50f, 100f,
                        false, stroke);
                c.drawArc(cx - s * 1.7f, cy - s * 1.7f, cx + s * 2.3f, cy + s * 1.7f, -46f, 92f,
                        false, stroke);
            } else {
                c.drawLine(cx + s * 0.9f, cy - s * 0.8f, cx + s * 1.9f, cy + s * 0.8f, stroke);
                c.drawLine(cx + s * 1.9f, cy - s * 0.8f, cx + s * 0.9f, cy + s * 0.8f, stroke);
            }
        }
    }

    // ------------------------------------------------------------------ screens

    private void drawMenu(Canvas c, Engine engine, float anim, float intro) {
        fill.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{Palette.alpha(0xFF05010F, 205), Palette.alpha(0xFF16042C, 165),
                        Palette.alpha(0xFF05010F, 240)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        float bob = (float) Math.sin(anim * 1.4f) * 6f * unit;
        float titleSize = 168f * unit;
        float y1 = h * 0.205f + bob;
        float base = y1 + titleSize * 1.02f;

        // reflection: mirrored around the baseline of "DRIFT", squashed and
        // clipped to a thin band so it can never collide with the title.
        c.save();
        c.clipRect(0f, base + 2f * unit, w, base + titleSize * 1.15f);
        c.translate(0f, base * 1.58f);
        c.scale(1f, -0.58f);
        drawTitleText(c, "NEON", persp.centreX, y1, titleSize, 1, 38);
        drawTitleText(c, "DRIFT", persp.centreX, base, titleSize, 2, 46);
        c.restore();

        drawTitleText(c, "NEON", persp.centreX, y1, titleSize, 1, 255);
        drawTitleText(c, "DRIFT", persp.centreX, y1 + titleSize * 1.02f, titleSize, 2, 255);

        label(c, "RUNNER  SANS  FIN", persp.centreX, base + titleSize * 1.18f + 34f * unit,
                28f * unit, Palette.alpha(Palette.TEXT_DIM, 255), 6f);

        // play button
        float pulse = 0.5f + 0.5f * (float) Math.sin(anim * 2.6f);
        drawButton(c, btnPlay, "JOUER", Palette.EDGE_RIGHT, Palette.EDGE_LEFT, true, pulse);

        // best score
        glowText(c, "RECORD  " + engine.best, persp.centreX, btnPlay.top - 46f * unit,
                40f * unit, Palette.GOLD, Palette.DANGER_C, Paint.Align.CENTER, 1.2f);

        // instructions
        label(c, "GLISSEZ LE DOIGT POUR ESQUIVER", persp.centreX, h * 0.845f, 26f * unit,
                Palette.TEXT, 2.4f);
        label(c, "EVITEZ LES BLOCS  •  COLLECTEZ LES ORBES", persp.centreX, h * 0.845f + 42f * unit,
                22f * unit, Palette.TEXT_DIM, 1.6f);
        label(c, "v1.0  •  NEON DRIFT", persp.centreX, h * 0.955f, 20f * unit,
                Palette.alpha(Palette.TEXT_DIM, 150), 1.2f);

        drawIconButton(c, btnSound, ICON_SPEAKER, soundOnState);
        drawIconButton(c, btnMusic, ICON_MUSIC, musicOnState);
    }

    private boolean soundOnState = true;
    private boolean musicOnState = true;

    public void setSoundState(boolean on) {
        soundOnState = on;
    }

    public void setMusicState(boolean on) {
        musicOnState = on;
    }

    private void drawTitleText(Canvas c, String s, float x, float y, float size, int which,
                               int alpha) {
        text.setTypeface(tfBlack);
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.CENTER);
        text.setLetterSpacing(0.06f);
        int main = which == 1 ? Palette.CITY_WINDOW_A : Palette.HORIZON;
        int glow = which == 1 ? Palette.EDGE_RIGHT : Palette.DANGER_A;
        if (alpha < 255) {
            // NB: setColor() would reset the alpha, so colour comes first.
            text.setColor(main);
            text.setAlpha(alpha);
            text.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
            c.drawText(s, x, y, text);
            text.setAlpha(255);
            return;
        }
        text.setShader(new LinearGradient(0, y - size * 0.8f, 0, y,
                Palette.WHITE, main, Shader.TileMode.CLAMP));
        text.setShadowLayer(0.28f * size, 0f, 0f, glow);
        c.drawText(s, x, y, text);
        text.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        text.setShader(null);
        // chromatic edges
        text.setAlpha(110);
        text.setColor(glow);
        c.drawText(s, x - 3f * unit, y, text);
        c.drawText(s, x + 3f * unit, y, text);
        text.setAlpha(255);
        text.setColor(main);
    }

    private void tpAlpha(int a) {
        text.setAlpha(a);
    }

    private void drawPaused(Canvas c, Engine engine, float anim) {
        fill.setColor(Palette.alpha(0xFF05010F, 215));
        c.drawRect(0, 0, w, h, fill);
        glowText(c, "PAUSE", persp.centreX, h * 0.33f, 120f * unit, Palette.TEXT,
                Palette.EDGE_RIGHT, Paint.Align.CENTER, 6f);
        label(c, "SCORE  " + engine.score(), persp.centreX, h * 0.395f, 34f * unit,
                Palette.TEXT_DIM, 1.5f);
        label(c, "RECORD  " + engine.best, persp.centreX, h * 0.44f, 26f * unit,
                Palette.alpha(Palette.GOLD, 200), 1.5f);
        drawButton(c, btnRetry, "REPRENDRE", Palette.EDGE_RIGHT, Palette.EDGE_LEFT, true, 0f);
        drawButton(c, btnMenu, "MENU", Palette.DANGER_A, Palette.DANGER_B, false, 0f);
    }

    private void drawGameOver(Canvas c, Engine engine, float anim, float t) {
        float k = clamp01(t / 0.55f);
        fill.setColor(Palette.alpha(0xFF05010F, (int) (205 * k)));
        c.drawRect(0, 0, w, h, fill);

        float rise = (1f - k) * 60f * unit;
        // glitchy title
        float ty = h * 0.30f + rise;
        float size = 132f * unit;
        glitchText(c, "CRASH", persp.centreX, ty, size, Palette.DANGER_A, anim);

        glowText(c, String.valueOf(engine.score()), persp.centreX, h * 0.435f + rise,
                132f * unit, Palette.TEXT, Palette.EDGE_RIGHT, Paint.Align.CENTER, 2f);
        label(c, "SCORE", persp.centreX, h * 0.435f + rise + 42f * unit, 26f * unit,
                Palette.TEXT_DIM, 3f);

        if (engine.newRecord) {
            float pulse = 0.6f + 0.4f * (float) Math.sin(anim * 6f);
            label(c, "NOUVEAU RECORD !", persp.centreX, h * 0.505f + rise, 34f * unit,
                    Palette.alpha(Palette.GOLD, (int) (170 + 85 * pulse)), 3.2f);
        } else {
            label(c, "RECORD  " + engine.best, persp.centreX, h * 0.505f + rise, 30f * unit,
                    Palette.alpha(Palette.GOLD, 210), 2f);
        }

        label(c, "ORBES  " + engine.orbs + "     VITESSE  " + (int) (engine.speed * 7.6f)
                        + " KM/H", persp.centreX, h * 0.545f + rise, 24f * unit,
                Palette.TEXT_DIM, 1.2f);

        drawButton(c, btnRetry, "REJOUER", Palette.EDGE_RIGHT, Palette.EDGE_LEFT, true, 0f);
        drawButton(c, btnMenu, "MENU", Palette.DANGER_A, Palette.DANGER_B, false, 0f);
    }

    private void glitchText(Canvas c, String s, float x, float y, float size, int color,
                            float anim) {
        float jitter = 2f + 3f * (float) Math.abs(Math.sin(anim * 11f));
        text.setTypeface(tfBlack);
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.CENTER);
        text.setLetterSpacing(0.04f);
        text.setShader(null);
        text.setShadowLayer(size * 0.22f, 0f, 0f, Palette.DANGER_B);
        text.setColor(Palette.alpha(Palette.CITY_WINDOW_A, 150));
        c.drawText(s, x - jitter * 6f, y, text);
        text.setColor(Palette.alpha(Palette.DANGER_B, 150));
        c.drawText(s, x + jitter * 6f, y, text);
        text.setColor(color);
        text.setAlpha(255);
        c.drawText(s, x, y, text);
        text.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
    }

    private void drawButton(Canvas c, RectF r, String caption, int colorA, int colorB,
                            boolean primary, float pulse) {
        float radius = r.height() * 0.5f;
        float glow = primary ? (0.55f + 0.45f * pulse) : 0.4f;

        // outer glow
        if (primary) {
            fill.setShader(null);
            fill.setColor(Palette.alpha(colorA, (int) (34 * glow)));
            rect2.set(r);
            rect2.inset(-r.height() * 0.16f, -r.height() * 0.16f);
            c.drawRoundRect(rect2, radius * 1.3f, radius * 1.3f, fill);
        }

        rect2.set(r);
        fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                Palette.alpha(Palette.mix(colorA, 0xFF000000, 0.55f), primary ? 235 : 170),
                Palette.alpha(Palette.mix(colorB, 0xFF000000, 0.68f), primary ? 225 : 165),
                Shader.TileMode.CLAMP));
        c.drawRoundRect(rect2, radius, radius, fill);
        fill.setShader(null);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.4f * unit);
        stroke.setColor(Palette.alpha(Palette.WHITE, primary ? 190 : 120));
        c.drawRoundRect(rect2, radius, radius, stroke);
        stroke.setStrokeWidth(6f * unit);
        stroke.setColor(Palette.alpha(colorA, (int) (70 * glow)));
        c.drawRoundRect(rect2, radius, radius, stroke);

        text.setTypeface(tfBlack);
        text.setTextSize(r.height() * 0.40f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setLetterSpacing(0.10f);
        text.setShader(null);
        text.setColor(Palette.WHITE);
        text.setAlpha(primary ? 255 : 225);
        text.setShadowLayer(r.height() * 0.12f, 0f, 0f, Palette.alpha(colorA, 220));
        float baseline = r.centerY() + text.getTextSize() * 0.36f;
        c.drawText(caption, r.centerX(), baseline, text);
        text.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
    }

    private void drawOverlay(Canvas c) {
        if (scanShader != null) {
            fill.setShader(scanShader);
            fill.setAlpha(70);
            c.drawRect(0, 0, w, h, fill);
            fill.setAlpha(255);
            fill.setShader(null);
        }
        if (vignette != null) {
            fill.setShader(vignette);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
        }
    }

    // ------------------------------------------------------------------- text

    private void label(Canvas c, String s, float x, float y, float size, int color, float sp) {
        label(c, s, x, y, size, color, sp, Paint.Align.CENTER);
    }

    private void label(Canvas c, String s, float x, float y, float size, int color, float sp,
                       Paint.Align align) {
        text.setTypeface(tfCondensed);
        text.setTextSize(size);
        text.setTextAlign(align);
        text.setLetterSpacing(sp / Math.max(1f, size));
        text.setShader(null);
        text.setColor(color);
        text.setAlpha((color >>> 24) & 0xFF);
        text.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        c.drawText(s, x, y, text);
    }

    private void glowText(Canvas c, String s, float x, float y, float size, int color,
                          int glow, Paint.Align align, float sp) {
        text.setTypeface(tfBlack);
        text.setTextSize(size);
        text.setTextAlign(align);
        text.setLetterSpacing(sp / Math.max(1f, size));
        text.setShader(null);
        text.setShadowLayer(size * 0.30f, 0f, 0f, Palette.alpha(glow, 220));
        text.setColor(color);
        text.setAlpha(255);
        c.drawText(s, x, y, text);
        text.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
    }

    // ------------------------------------------------------------------ helper

    private Paint solid(int color) {
        Paint p = new Paint();
        p.setColor(color);
        p.setShader(null);
        return p;
    }

    private static float clamp01(float v) {
        if (v < 0f) {
            return 0f;
        }
        return v > 1f ? 1f : v;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
