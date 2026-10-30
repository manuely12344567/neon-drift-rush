import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.neondrift.rush.NeonRenderer;
import com.neondrift.rush.core.Engine;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for the crash reported on a real phone: the game opened, ran
 * for a couple of seconds, then closed by itself.
 *
 * <p>Cause: {@code NeonRenderer.resize()} is called by the view on the UI thread
 * (from {@code surfaceChanged}) but it rebuilt its cached bitmaps and called
 * {@code recycle()} on the previous ones, while the render thread was very
 * probably drawing with them. A single relayout - which Android performs a
 * second or two after launch when the immersive mode hides the system bars -
 * was enough to trigger "Canvas: trying to use a recycled bitmap" and kill the
 * process.
 *
 * <p>These tests fail on the buggy version and pass once the size change is
 * deferred to the render thread and bitmaps are no longer recycled.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class ResizeRegressionTest {

    private static final int W = 1080;
    private static final int H = 1920;

    /** A surface resize must never invalidate bitmaps a frame may still use. */
    @Test
    public void resizeMustNotRecycleBitmapsStillInUse() throws Exception {
        NeonRenderer renderer = new NeonRenderer();
        renderer.resize(W, H);

        Bitmap frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        Engine engine = new Engine(1L);
        engine.reset(0);

        renderer.draw(canvas, engine, NeonRenderer.MODE_PLAY, 1f, 1f / 60f, true, true, 0f);

        Bitmap sky = (Bitmap) read(renderer, "background");
        Bitmap skyline = (Bitmap) read(renderer, "skyline");
        assertNotNull("les couches statiques doivent être construites", sky);
        assertNotNull(skyline);

        // The window relayouts (immersive mode) while the render thread works.
        renderer.resize(W, 2400);

        assertFalse("le ciel a été recyclé alors qu'une frame pouvait le dessiner",
                sky.isRecycled());
        assertFalse("la skyline a été recyclée alors qu'une frame pouvait la dessiner",
                skyline.isRecycled());

        // Drawing with the bitmap captured before the resize must still work:
        // on the buggy version this line throws RuntimeException.
        Canvas probe = new Canvas(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888));
        probe.drawBitmap(sky, 0, 0, null);
        probe.drawBitmap(skyline, 0, 0, null);
    }

    /** The new size must be picked up by the render thread on its next frame. */
    @Test
    public void newSizeIsAppliedOnNextFrame() throws Exception {
        NeonRenderer renderer = new NeonRenderer();
        renderer.resize(W, H);

        Bitmap frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        Engine engine = new Engine(2L);
        engine.reset(0);

        renderer.draw(canvas, engine, NeonRenderer.MODE_PLAY, 1f, 1f / 60f, true, true, 0f);
        assertEquals(W, ((Float) read(renderer, "w")).intValue());
        assertEquals(H, ((Float) read(renderer, "h")).intValue());

        renderer.resize(720, 1280);
        Bitmap small = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888);
        renderer.draw(new Canvas(small), engine, NeonRenderer.MODE_PLAY, 1f, 1f / 60f,
                true, true, 0f);

        assertEquals("la nouvelle largeur doit être appliquée", 720f,
                (Float) read(renderer, "w"), 0.5f);
        assertEquals("la nouvelle hauteur doit être appliquée", 1280f,
                (Float) read(renderer, "h"), 0.5f);

        // and the layout must follow, otherwise the touch targets are wrong
        android.graphics.RectF pause = renderer.pauseButton();
        assertTrue("le bouton pause doit rester dans l'écran", pause.right <= 720.5f);
    }

    /** A resize storm (window insets animating, rotation, split screen...). */
    @Test
    public void resizeStormDoesNotLeakOrCrash() throws Exception {
        NeonRenderer renderer = new NeonRenderer();
        Engine engine = new Engine(3L);
        engine.reset(0);
        int[][] sizes = {{540, 960}, {480, 854}, {360, 640}};
        for (int i = 0; i < 40; i++) {
            int[] s = sizes[i % sizes.length];
            renderer.resize(s[0], s[1]);
            Bitmap bmp = Bitmap.createBitmap(s[0], s[1], Bitmap.Config.ARGB_8888);
            renderer.draw(new Canvas(bmp), engine, NeonRenderer.MODE_MENU, i * 0.1f,
                    1f / 60f, true, true, 0f);
            bmp.recycle();
        }
        assertNotNull(read(renderer, "background"));
    }

    private static Object read(Object target, String field) throws Exception {
        Field f = NeonRenderer.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }
}
