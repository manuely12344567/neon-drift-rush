package com.neondrift.rush.core;

/**
 * Pseudo-3D "outrun" projection: a flat track seen from a camera floating
 * above it.
 *
 * <p>Plain 1/d perspective looks gorgeous but compresses the near field so
 * violently that an obstacle only becomes readable a tenth of a second before
 * impact. This projection therefore uses a power curve on the depth instead:
 *
 * <pre>
 *   q       = d / FAR_D                        (0 = camera, 1 = spawn line)
 *   y       = horizonY + (h - horizonY) * (1 - q)^GAMMA
 *   halfW   = ROAD_HALF * w * (y - horizonY) / (h - horizonY)
 *   screenX = centreX + lateral * halfW
 *   scale   = (y - horizonY) / (playerY - horizonY)
 * </pre>
 *
 * Because screenX is linear in (y - horizonY), lane lines stay perfectly
 * straight and converge on the vanishing point: it reads as real perspective,
 * yet equal slices of world depth get a fair share of the screen.
 */
public final class Perspective {

    /** Player distance ahead of the camera, in world units. */
    public static final float PLAYER_D = Engine.PLAYER_D;
    /** Curve of the depth mapping. 1 = linear, higher = more perspective-like. */
    private static final float GAMMA = 2.06f;
    /** Half width of the track at the bottom of the screen, as a share of w. */
    private static final float ROAD_HALF = 0.455f;

    public float width;
    public float height;
    public float centreX;
    public float horizonY;
    /** Screen Y of the player ship. */
    public float playerY;
    /** Normalised track width factor at the player depth. */
    public float playerFactor = 1f;

    public void configure(float width, float height) {
        this.width = width;
        this.height = height;
        this.centreX = width * 0.5f;
        this.horizonY = height * 0.315f;
        this.playerY = height * 0.845f;
        this.playerFactor = factor(Engine.PLAYER_D);
        if (playerFactor < 0.05f) {
            playerFactor = 0.05f;
        }
    }

    /** Normalised track width at depth d: 1 at the bottom of the screen. */
    public float factor(float d) {
        float q = d / Engine.FAR_D;
        if (q < 0f) {
            q = 0f;
        }
        if (q > 1f) {
            q = 1f;
        }
        float k = 1f - q;
        return (float) Math.pow(k, GAMMA);
    }

    public float screenY(float d) {
        return horizonY + (height - horizonY) * factor(d);
    }

    public float screenX(float lateral, float d) {
        return centreX + lateral * width * ROAD_HALF * factor(d);
    }

    /** Half width, in pixels, of the track at depth d. */
    public float halfWidthPx(float d) {
        return width * ROAD_HALF * factor(d);
    }

    /** Sprite scale at depth d: 1.0 exactly where the player is. */
    public float scale(float d) {
        return factor(d) / playerFactor;
    }

    /** Converts a screen X back into a lateral track position at depth d. */
    public float lateral(float screenX, float d) {
        float f = width * ROAD_HALF * factor(d);
        if (f < 0.001f) {
            f = 0.001f;
        }
        return (screenX - centreX) / f;
    }

    /** Lateral position of a touch, expressed at the player depth. */
    public float lateralAtPlayer(float screenX) {
        float f = width * ROAD_HALF * playerFactor;
        if (f < 0.001f) {
            f = 0.001f;
        }
        return (screenX - centreX) / f;
    }
}
