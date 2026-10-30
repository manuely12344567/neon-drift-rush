package com.neondrift.rush.core;

import java.util.ArrayList;
import java.util.Random;

/**
 * The whole gameplay simulation of NEON DRIFT.
 *
 * <p>Deliberately free of any Android import: it can be driven by a plain JVM

 * test harness (see {@code test/EngineTest.java}) frame by frame, which is how
 * the balance of the game is verified before shipping the APK.
 *
 * <p>Coordinate system
 * <ul>
 *   <li>{@code d} is the distance ahead of the camera, in world units. The
 *       camera sits at {@code d = 0}, the player at {@link #PLAYER_D}.</li>
 *   <li>{@code x} is the lateral position on the track, from -1 (left edge) to
 *       +1 (right edge).</li>
 * </ul>
 */
public final class Engine {

    // ------------------------------------------------------------------ tuning

    /**
     * Distance of the player ahead of the camera. Together with FAR_D it sets
     * how long an obstacle stays on screen: (FAR_D - PLAYER_D) / speed seconds.
     */
    public static final float PLAYER_D = 24.0f;
    /** Distance at which obstacles pop into existence (the horizon). */
    public static final float FAR_D = 140.0f;

    /** Lateral position of the three lanes. */
    public static final float[] LANE_X = {-0.615f, 0.0f, 0.615f};

    /** Player movement bounds on the track. */
    public static final float TRACK_LIMIT = 0.86f;

    /** Half width of the player hitbox, in lateral units. */
    public static final float PLAYER_HALF_W = 0.125f;
    /** Half width of an obstacle hitbox, in lateral units. */
    public static final float OBSTACLE_HALF_W = 0.175f;
    /** Half width of an orb hitbox, in lateral units. */
    public static final float ORB_HALF_W = 0.215f;
    /**
     * Extra lateral distance for a "near miss" to register. Tuned so that
     * slipping past an obstacle in the neighbouring lane always counts
     * (0.615 - 0.30 = 0.315 of gap) while skipping a lane in-between does not.
     */
    public static final float NEAR_MISS_MARGIN = 0.42f;

    /** Speed at the very beginning of a run, in world units per second. */
    public static final float MIN_SPEED = 26f;
    /** Terminal speed reached after a long run. */
    public static final float MAX_SPEED = 56f;
    /** Distance needed to complete roughly 63% of the speed ramp. */
    private static final float SPEED_RAMP = 2000f;

    /** Fastest lateral movement of the ship, in lateral units per second. */
    private static final float LATERAL_RESPONSE = 9.5f;
    private static final float MAX_LATERAL_SPEED = 2.0f;

    /** Seconds between two obstacle rows, at the start and at full speed. */
    private static final float SPAWN_INTERVAL_START = 1.45f;
    private static final float SPAWN_INTERVAL_END = 0.62f;

    /** Seconds the combo multiplier stays alive without a new event. */
    private static final float COMBO_WINDOW = 2.6f;
    private static final int COMBO_MAX = 8;

    private static final int POINTS_ORB = 25;
    private static final int POINTS_NEAR_MISS = 12;

    // ------------------------------------------------------------------- state

    public final ArrayList<Entity> entities = new ArrayList<Entity>(48);
    public final Events events = new Events();

    private final Random random;
    private final ArrayList<Entity> pool = new ArrayList<Entity>(64);

    /** Player lateral position on the track. */
    public float playerX;
    /** Player lateral velocity (lateral units / second), used for banking FX. */
    public float playerVX;
    /** Where the player wants to be (set by touch input). */
    public float targetX;

    /** Total distance travelled this run. */
    public float travel;
    /** Current scroll speed in world units per second. */
    public float speed;
    /** Raw score, kept as float so tiny per-frame gains are not lost. */
    public float scoreF;
    public int orbs;

    public int combo;
    public float comboTimer;

    public boolean alive;
    /** Seconds elapsed since the crash. */
    public float deathTimer;
    /** Seconds elapsed since the run started (used by the HUD / FX). */
    public float runTime;

    public int best;
    public boolean newRecord;

    /**
     * Attract mode: the menu keeps the world alive behind the title. No
     * obstacles are spawned and nothing can kill the ship, so it is purely a
     * moving backdrop for the start screen.
     */
    public boolean demo;

    /** Current difficulty, 0..1. */
    public float difficulty;

    private float spawnTimer;
    private float playerPrevX;
    private int lastFreeLane = 1;

    public Engine() {
        this(20251030L);
    }

    public Engine(long seed) {
        this.random = new Random(seed);
        reset(0);
    }

    // --------------------------------------------------------------- lifecycle

    /** Restarts a run, keeping the provided personal best. */
    public void reset(int bestScore) {
        for (int i = 0; i < entities.size(); i++) {
            pool.add(entities.get(i));
        }
        entities.clear();
        events.clear();

        playerX = 0f;
        playerPrevX = 0f;
        playerVX = 0f;
        targetX = 0f;

        travel = 0f;
        speed = MIN_SPEED;
        scoreF = 0f;
        orbs = 0;
        combo = 0;
        comboTimer = 0f;
        alive = true;
        deathTimer = 0f;
        runTime = 0f;
        difficulty = 0f;
        newRecord = false;
        best = bestScore;
        spawnTimer = 1.35f;
        lastFreeLane = 1;
    }

    /** Ends the run immediately (used by the "give up" button). */
    public void crash() {
        if (!alive) {
            return;
        }
        alive = false;
        deathTimer = 0f;
        events.push(Events.CRASH, playerX, PLAYER_D);
    }

    // ------------------------------------------------------------------- input

    /** Moves the ship toward a lateral target, clamped to the track. */
    public void setTargetX(float x) {
        if (!finite(x)) {
            // NaN / infinite input used to be stored as-is; NaN then survived
            // every clamp below, so playerX became NaN for ever, the ship was
            // never drawn again and steering stopped responding.
            targetX = 0f;
            return;
        }
        if (x < -TRACK_LIMIT) {
            x = -TRACK_LIMIT;
        }
        if (x > TRACK_LIMIT) {
            x = TRACK_LIMIT;
        }
        targetX = x;
    }

    /** Shifts the target by a whole lane (used by keyboard / accessibility). */
    public void nudgeLane(int dir) {
        float x = targetX + dir * (LANE_X[2] - LANE_X[1]);
        setTargetX(x);
    }

    /** Current score, floored to an int. */
    public int score() {
        return (int) scoreF;
    }

    /** Score multiplier granted by the running combo. */
    public int multiplier() {
        return 1 + Math.min(combo, COMBO_MAX);
    }

    // ------------------------------------------------------------------ update

    /**
     * Advances the simulation.
     *
     * @param dt elapsed time in seconds
     */
    public void update(float dt) {
        if (dt < 0f) {
            dt = 0f;
        }
        if (dt > 0.05f) {
            dt = 0.05f; // never tunnel through obstacles after a stall
        }
        events.clear();
        sanitize();

        if (!alive) {
            deathTimer += dt;
            advanceEntityPhases(dt);
            return;
        }

        runTime += dt;
        difficulty = 1f - (float) Math.exp(-travel / 3200f);

        speed = MIN_SPEED + (MAX_SPEED - MIN_SPEED) * (1f - (float) Math.exp(-travel / SPEED_RAMP));
        if (demo) {
            speed = Math.min(speed, 74f);
        }
        travel += speed * dt;

        updatePlayer(dt);

        scoreF += dt * (5f + speed * 1.1f);

        if (comboTimer > 0f) {
            comboTimer -= dt;
            if (comboTimer <= 0f) {
                if (combo > 0) {
                    events.push(Events.COMBO_LOST, playerX, PLAYER_D);
                }
                combo = 0;
                comboTimer = 0f;
            }
        }

        updateEntities(dt);
        updateSpawning(dt);

        if (!newRecord && best > 0 && score() > best) {
            newRecord = true;
            events.push(Events.NEW_RECORD, playerX, PLAYER_D);
        }
    }

    /** True for every value a float can hold except NaN and +/-infinity. */
    static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    /**
     * Repairs any state that has gone NaN or infinite.
     *
     * <p>{@code NaN} is not clamped by {@code if (x < -LIMIT) ...}: every
     * comparison against it is false, so a single bad value propagates for
     * ever. That is exactly how the ship could vanish for good on a device
     * while the world kept scrolling. Called once per frame, it costs a few
     * comparisons and makes that whole class of bug impossible.
     */
    public void sanitize() {
        if (!finite(playerX)) {
            playerX = 0f;
            playerPrevX = 0f;
            playerVX = 0f;
        }
        if (!finite(playerPrevX)) {
            playerPrevX = playerX;
        }
        if (!finite(targetX)) {
            targetX = 0f;
        }
        if (!finite(playerVX)) {
            playerVX = 0f;
        }
        if (!finite(travel)) {
            travel = 0f;
        }
        if (!finite(speed) || speed < MIN_SPEED || speed > MAX_SPEED * 2f) {
            speed = MIN_SPEED;
        }
        if (!finite(scoreF)) {
            scoreF = 0f;
        }
        if (!finite(comboTimer)) {
            comboTimer = 0f;
        }
        if (!finite(deathTimer)) {
            deathTimer = 0f;
        }
        if (!finite(runTime)) {
            runTime = 0f;
        }
        if (!finite(difficulty)) {
            difficulty = 0f;
        }
        if (playerX < -TRACK_LIMIT) {
            playerX = -TRACK_LIMIT;
        } else if (playerX > TRACK_LIMIT) {
            playerX = TRACK_LIMIT;
        }
        if (targetX < -TRACK_LIMIT) {
            targetX = -TRACK_LIMIT;
        } else if (targetX > TRACK_LIMIT) {
            targetX = TRACK_LIMIT;
        }
    }

    private void updatePlayer(float dt) {
        playerPrevX = playerX;
        float delta = targetX - playerX;
        float step = delta * Math.min(1f, LATERAL_RESPONSE * dt);
        float maxStep = MAX_LATERAL_SPEED * dt;
        if (step > maxStep) {
            step = maxStep;
        } else if (step < -maxStep) {
            step = -maxStep;
        }
        playerX += step;
        if (playerX < -TRACK_LIMIT) {
            playerX = -TRACK_LIMIT;
        }
        if (playerX > TRACK_LIMIT) {
            playerX = TRACK_LIMIT;
        }
        if (dt > 0.0001f) {
            float vx = (playerX - playerPrevX) / dt;
            playerVX += (vx - playerVX) * Math.min(1f, 12f * dt);
        }
    }

    private void advanceEntityPhases(float dt) {
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            e.phase += dt * (e.type == Entity.TYPE_ORB ? 3.2f : 1.6f);
        }
    }

    private void updateEntities(float dt) {
        for (int i = entities.size() - 1; i >= 0; i--) {
            Entity e = entities.get(i);
            if (!finite(e.d) || !finite(e.x)) {
                entities.remove(i);
                pool.add(e);
                continue;
            }
            e.prevD = e.d;
            e.d -= speed * dt;
            e.phase += dt * (e.type == Entity.TYPE_ORB ? 3.2f : 1.6f);

            if (!e.resolved && crossedPlayer(e)) {
                resolve(e);
            }
            if (e.d < -3f || e.dead) {
                entities.remove(i);
                pool.add(e);
            }
        }
    }

    /** True if the entity swept past the player plane during this frame. */
    private boolean crossedPlayer(Entity e) {
        return e.prevD > PLAYER_D && e.d <= PLAYER_D;
    }

    private void resolve(Entity e) {
        float dx = Math.abs(e.x - playerX);
        if (e.type == Entity.TYPE_ORB) {
            if (dx <= ORB_HALF_W + PLAYER_HALF_W) {
                e.resolved = true;
                e.dead = true;
                orbs++;
                combo = Math.min(combo + 1, COMBO_MAX);
                comboTimer = COMBO_WINDOW;
                scoreF += POINTS_ORB * multiplier();
                events.push(Events.ORB_PICKED, e.x, e.d);
            }
            return;
        }

        if (dx <= OBSTACLE_HALF_W + PLAYER_HALF_W) {
            e.resolved = true;
            if (demo) {
                return; // the attract-mode ship is indestructible
            }
            crash();
        } else if (dx <= OBSTACLE_HALF_W + PLAYER_HALF_W + NEAR_MISS_MARGIN) {
            e.resolved = true;
            combo = Math.min(combo + 1, COMBO_MAX);
            comboTimer = COMBO_WINDOW;
            scoreF += POINTS_NEAR_MISS * multiplier();
            events.push(Events.NEAR_MISS, e.x, e.d);
        }
    }

    // ---------------------------------------------------------------- spawning

    private void updateSpawning(float dt) {
        spawnTimer -= dt;
        if (spawnTimer > 0f) {
            return;
        }
        if (demo) {
            spawnDemoOrbs();
            spawnTimer = 0.8f + random.nextFloat() * 0.5f;
            return;
        }
        spawnRow();
        float interval = SPAWN_INTERVAL_START
                + (SPAWN_INTERVAL_END - SPAWN_INTERVAL_START) * difficulty;
        spawnTimer = interval * (0.86f + random.nextFloat() * 0.3f);
    }

    /** Attract mode only drops pickups, in a random lane. */
    private void spawnDemoOrbs() {
        int lane = random.nextInt(LANE_X.length);
        Entity orb = acquire();
        orb.set(Entity.TYPE_ORB, FAR_D, LANE_X[lane], lane, 0);
        entities.add(orb);
        if (random.nextFloat() < 0.4f) {
            Entity orb2 = acquire();
            orb2.set(Entity.TYPE_ORB, FAR_D + 10f, LANE_X[lane], lane, 0);
            entities.add(orb2);
        }
    }

    /** Nearest orb ahead of the ship, used by the attract-mode autopilot. */
    public float nearestOrbX() {
        float bestD = Float.MAX_VALUE;
        float x = 0f;
        for (int i = 0; i < entities.size(); i++) {
            Entity en = entities.get(i);
            if (en.type == Entity.TYPE_ORB && en.d > PLAYER_D && en.d < bestD) {
                bestD = en.d;
                x = en.x;
            }
        }
        return x;
    }

    private Entity acquire() {
        if (!pool.isEmpty()) {
            return pool.remove(pool.size() - 1);
        }
        return new Entity();
    }

    /**
     * Spawns one row of obstacles plus optional pickups.
     *
     * <p>Fairness rule: at least one lane is always free, and that free lane is
     * never more than one lane away from the previous row's free lane, so the
     * row is always reachable from where the player already is.
     */
    private void spawnRow() {
        int freeLane = pickReachableFreeLane();
        int blockedCount = random.nextFloat() < (0.18f + 0.42f * difficulty) ? 2 : 1;

        boolean[] blocked = new boolean[LANE_X.length];
        blocked[freeLane] = false;
        int remaining = blockedCount;
        while (remaining > 0) {
            int lane = random.nextInt(LANE_X.length);
            if (lane == freeLane || blocked[lane]) {
                continue;
            }
            blocked[lane] = true;
            remaining--;
        }

        for (int lane = 0; lane < LANE_X.length; lane++) {
            if (!blocked[lane]) {
                continue;
            }
            Entity e = acquire();
            int variant = random.nextInt(3);
            if (variant == Entity.VARIANT_GATE && blockedCount == 1) {
                variant = Entity.VARIANT_BLOCK;
            }
            e.set(Entity.TYPE_OBSTACLE, FAR_D, LANE_X[lane], lane, variant);
            entities.add(e);
        }

        // Rewards are placed in the lane the player is invited to take.
        if (random.nextFloat() < 0.72f) {
            Entity orb = acquire();
            orb.set(Entity.TYPE_ORB, FAR_D, LANE_X[freeLane], freeLane, 0);
            entities.add(orb);
            if (random.nextFloat() < 0.45f) {
                Entity orb2 = acquire();
                orb2.set(Entity.TYPE_ORB, FAR_D + 9f, LANE_X[freeLane], freeLane, 0);
                entities.add(orb2);
            }
        }

        lastFreeLane = freeLane;
    }

    private int pickReachableFreeLane() {
        int[] candidates = new int[LANE_X.length];
        int count = 0;
        for (int lane = 0; lane < LANE_X.length; lane++) {
            if (Math.abs(lane - lastFreeLane) <= 1) {
                candidates[count++] = lane;
            }
        }
        if (count == 0) {
            return lastFreeLane;
        }
        return candidates[random.nextInt(count)];
    }

    // ------------------------------------------------------------------ helper

    /** Number of live obstacles on screen; used by the tests. */
    public int obstacleCount() {
        int n = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (entities.get(i).type == Entity.TYPE_OBSTACLE && !entities.get(i).dead) {
                n++;
            }
        }
        return n;
    }

    /** Number of live orbs on screen; used by the tests. */
    public int orbCount() {
        int n = 0;
        for (int i = 0; i < entities.size(); i++) {
            if (entities.get(i).type == Entity.TYPE_ORB && !entities.get(i).dead) {
                n++;
            }
        }
        return n;
    }
}
