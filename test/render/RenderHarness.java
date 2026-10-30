import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.neondrift.rush.NeonRenderer;
import com.neondrift.rush.core.Engine;
import com.neondrift.rush.core.Entity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.FileOutputStream;

/**
 * Renders the real NeonRenderer off-device (Robolectric, native graphics) and
 * writes PNG screenshots. This is how the visuals are checked without an
 * emulator: the exact same drawing code that ships in the APK is executed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class RenderHarness {

    private static final int W = 1080;
    private static final int H = 2280;
    private static final String OUT = System.getProperty("neondrift.out", "screenshots/");

    @Test
    public void renderAllScreens() throws Exception {
        NeonRenderer renderer = new NeonRenderer();
        renderer.resize(W, H);

        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // ---------------------------------------------------------- menu -----
        Engine menu = new Engine(7L);
        menu.demo = true;
        menu.reset(12480);
        for (int i = 0; i < 200; i++) {
            menu.setTargetX(menu.nearestOrbX());
            menu.update(1f / 60f);
        }
        pump(renderer, canvas, menu, NeonRenderer.MODE_MENU, 60);
        save(bmp, "screen_menu.png");

        // ------------------------------------------------------ gameplay -----
        Engine play = new Engine(20251030L);
        play.reset(12480);
        int frames = 0;
        while (frames < 60 * 42) {
            autopilot(play);
            play.update(1f / 60f);
            renderer.handleEvents(play);
            if (!play.alive) {
                play.reset(12480);
            }
            frames++;
        }
        System.out.println("DEBUG entities=" + play.entities.size()
                + " obstacles=" + play.obstacleCount() + " orbs=" + play.orbCount()
                + " alive=" + play.alive + " demo=" + play.demo
                + " score=" + play.score());
        for (int i = 0; i < play.entities.size() && i < 12; i++) {
            Entity d = play.entities.get(i);
            System.out.println("  e[" + i + "] type=" + d.type + " d=" + d.d + " x=" + d.x
                    + " variant=" + d.variant + " dead=" + d.dead);
        }
        // a few extra frames so the particles are mid-flight
        pump(renderer, canvas, play, NeonRenderer.MODE_PLAY, 8);
        save(bmp, "screen_gameplay.png");

        // ------------------------------------------------------ paused -------
        pump(renderer, canvas, play, NeonRenderer.MODE_PAUSED, 2);
        save(bmp, "screen_pause.png");

        // --------------------------------------------------- game over -------
        Engine over = new Engine(99L);
        over.reset(9000);
        for (int i = 0; i < 60 * 30; i++) {
            autopilot(over);
            over.update(1f / 60f);
            if (!over.alive) {
                break;
            }
        }
        over.crash();
        over.newRecord = true;
        for (int i = 0; i < 30; i++) {
            over.update(1f / 60f);
        }
        pump(renderer, canvas, over, NeonRenderer.MODE_OVER, 60, 1.2f);
        save(bmp, "screen_gameover.png");
    }

    /**
     * Robustness sweep: every screen size, every mode, an empty world, a dense
     * world and a world with more entities than the renderer's sorting buffer.
     * Any exception here would be a crash on a real device.
     */
    @Test
    public void renderEverySizeAndMode() {
        int[][] sizes = {{320, 480}, {480, 800}, {720, 1280}, {1080, 1920},
                {1080, 2400}, {1440, 3200}, {800, 600}};
        int drawn = 0;
        for (int[] size : sizes) {
            NeonRenderer renderer = new NeonRenderer();
            renderer.resize(size[0], size[1]);
            Bitmap bmp = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);

            Engine engine = new Engine(1234L);
            engine.reset(500);
            for (int mode = 0; mode <= 3; mode++) {
                renderer.draw(canvas, engine, mode, 2.5f, 1f / 60f, true, true,
                        mode == 3 ? 2f : 0f);
                drawn++;
            }
            // dense world, more entities than the sorting buffer
            for (int i = 0; i < 200; i++) {
                Entity e = new Entity();
                int type = i % 2;
                e.set(type, 2f + (i % 40) * 3.4f, ((i % 3) - 1) * 0.615f, i % 3, i % 3);
                engine.entities.add(e);
            }
            for (int mode = 0; mode <= 3; mode++) {
                renderer.draw(canvas, engine, mode, 1.7f, 1f / 60f, false, false, 0.3f);
                drawn++;
            }
            // and a tiny surface, to exercise the layout maths
            NeonRenderer tiny = new NeonRenderer();
            tiny.resize(120, 200);
            Bitmap small = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888);
            tiny.draw(new Canvas(small), engine, NeonRenderer.MODE_PLAY, 1f, 1f / 60f, true, true,
                    0f);
            drawn++;
        }
        System.out.println("ROBUSTNESS: rendered " + drawn
                + " frames across " + sizes.length + " screen sizes without exception");
    }

    private void pump(NeonRenderer renderer, Canvas canvas, Engine engine, int mode, int frames) {
        pump(renderer, canvas, engine, mode, frames, 0f);
    }

    /** Draws `frames` frames so animations/particles reach a natural state. */
    private void pump(NeonRenderer renderer, Canvas canvas, Engine engine, int mode, int frames,
                      float death) {
        for (int i = 0; i < frames; i++) {
            renderer.draw(canvas, engine, mode, 3.2f + i / 60f, 1f / 60f, true,
                    death + i / 60f);
        }
    }

    private void save(Bitmap bmp, String name) throws Exception {
        try (FileOutputStream out = new FileOutputStream(OUT + name)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        System.out.println("wrote " + OUT + name);
    }

    /** Keeps the ship alive so the screenshot shows a realistic mid-run frame. */
    private void autopilot(Engine e) {
        float rowD = Float.MAX_VALUE;
        for (int i = 0; i < e.entities.size(); i++) {
            Entity en = e.entities.get(i);
            if (en.type == Entity.TYPE_OBSTACLE && en.d > Engine.PLAYER_D - 1f) {
                rowD = Math.min(rowD, en.d);
            }
        }
        if (rowD == Float.MAX_VALUE) {
            return;
        }
        boolean[] blocked = new boolean[Engine.LANE_X.length];
        for (int i = 0; i < e.entities.size(); i++) {
            Entity en = e.entities.get(i);
            if (en.type == Entity.TYPE_OBSTACLE && Math.abs(en.d - rowD) < 2.5f) {
                blocked[en.lane] = true;
            }
        }
        int bestLane = -1;
        float bestDist = Float.MAX_VALUE;
        for (int lane = 0; lane < blocked.length; lane++) {
            if (blocked[lane]) {
                continue;
            }
            float dist = Math.abs(Engine.LANE_X[lane] - e.playerX);
            if (dist < bestDist) {
                bestDist = dist;
                bestLane = lane;
            }
        }
        if (bestLane >= 0) {
            e.setTargetX(Engine.LANE_X[bestLane]);
        }
    }
}
