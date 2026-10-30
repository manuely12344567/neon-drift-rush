package com.neondrift.rush.core;

/**
 * Gameplay events produced by {@link Engine} during an update. The view layer
 * drains them every frame to fire sounds, particles and screen shake.
 */
public final class Events {

    /** An energy orb was collected. */
    public static final int ORB_PICKED = 0;
    /** The player dodged an obstacle very closely. */
    public static final int NEAR_MISS = 1;
    /** The player hit an obstacle: run over. */
    public static final int CRASH = 2;
    /** A new personal record has just been beaten. */
    public static final int NEW_RECORD = 3;
    /** Combo multiplier lost after the window expired. */
    public static final int COMBO_LOST = 4;

    /** Maximum number of events buffered per frame. */
    private static final int CAPACITY = 16;

    private final int[] types = new int[CAPACITY];
    private final float[] xs = new float[CAPACITY];
    private final float[] ds = new float[CAPACITY];
    private int count;

    public void clear() {
        count = 0;
    }

    public void push(int type, float x, float d) {
        if (count >= CAPACITY) {
            return;
        }
        types[count] = type;
        xs[count] = x;
        ds[count] = d;
        count++;
    }

    public int size() {
        return count;
    }

    public int type(int i) {
        return types[i];
    }

    public float x(int i) {
        return xs[i];
    }

    public float d(int i) {
        return ds[i];
    }
}
