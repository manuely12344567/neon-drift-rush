package com.neondrift.rush;

/** Colour palette of NEON DRIFT (synthwave / outrun). */
public final class Palette {

    public static final int BG_TOP = 0xFF05010F;
    public static final int BG_MID = 0xFF1A0538;
    public static final int BG_LOW = 0xFF43105F;
    public static final int HORIZON = 0xFFFF2E88;

    public static final int SUN_TOP = 0xFFFFE07A;
    public static final int SUN_BOTTOM = 0xFFFF2E88;

    public static final int CITY = 0xFF150726;
    public static final int CITY_WINDOW_A = 0xFF4DE3FF;
    public static final int CITY_WINDOW_B = 0xFFFF6FD8;

    public static final int ROAD = 0xFF0A0418;
    public static final int ROAD_GRID = 0xFF37F0FF;
    public static final int EDGE_LEFT = 0xFFFF2E88;
    public static final int EDGE_RIGHT = 0xFF2EF2FF;

    public static final int SHIP_BODY_TOP = 0xFFFFFFFF;
    public static final int SHIP_BODY_BOTTOM = 0xFF6E5BC8;
    public static final int SHIP_ACCENT = 0xFF7CF9FF;
    public static final int THRUSTER = 0xFF8AF0FF;

    public static final int ORB_CORE = 0xFFFFF3B0;
    public static final int ORB_GLOW = 0xFFFFB300;

    public static final int DANGER_A = 0xFFFF1F6B;
    public static final int DANGER_B = 0xFFB44BFF;
    public static final int DANGER_C = 0xFFFF7A18;

    public static final int TEXT = 0xFFEAF6FF;
    public static final int TEXT_DIM = 0xFF93A9C6;
    public static final int GOLD = 0xFFFFD166;
    public static final int WHITE = 0xFFFFFFFF;

    public static int alpha(int color, int a) {
        if (a < 0) {
            a = 0;
        }
        if (a > 255) {
            a = 255;
        }
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** Linear interpolation between two ARGB colours. */
    public static int mix(int c1, int c2, float t) {
        if (t < 0f) {
            t = 0f;
        }
        if (t > 1f) {
            t = 1f;
        }
        int a1 = (c1 >>> 24) & 0xFF;
        int r1 = (c1 >> 16) & 0xFF;
        int g1 = (c1 >> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        int a2 = (c2 >>> 24) & 0xFF;
        int r2 = (c2 >> 16) & 0xFF;
        int g2 = (c2 >> 8) & 0xFF;
        int b2 = c2 & 0xFF;
        int a = (int) (a1 + (a2 - a1) * t);
        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private Palette() {
    }
}
