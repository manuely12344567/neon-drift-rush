package com.neondrift.rush.core;

/**
 * A single object living on the track: an obstacle to dodge or an energy orb
 * to collect.
 *
 * <p>Pure data, no Android dependency, so the whole simulation can be unit
 * tested on a plain JVM.
 */
public final class Entity {

    public static final int TYPE_OBSTACLE = 0;
    public static final int TYPE_ORB = 1;

    /** Obstacle variants, only used for visual variety. */
    public static final int VARIANT_PILLAR = 0;
    public static final int VARIANT_BLOCK = 1;
    public static final int VARIANT_GATE = 2;

    public int type;
    /** Distance ahead of the camera, in world units. Decreases over time. */
    public float d;
    /** Distance at the previous simulation step (used for swept collision). */
    public float prevD;
    /** Lateral position on the track: -1 = left edge, +1 = right edge. */
    public float x;
    /** Index of the lane the entity was spawned in. */
    public int lane;
    public int variant;
    /** Animated phase, advanced by the renderer. */
    public float phase;
    /** True once the entity has been consumed (hit / collected). */
    public boolean resolved;
    /** Set when the entity should be removed at the end of the frame. */
    public boolean dead;

    public void set(int type, float d, float x, int lane, int variant) {
        this.type = type;
        this.d = d;
        this.prevD = d;
        this.x = x;
        this.lane = lane;
        this.variant = variant;
        this.phase = 0f;
        this.resolved = false;
        this.dead = false;
    }
}
