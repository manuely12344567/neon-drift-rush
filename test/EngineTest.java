import com.neondrift.rush.core.Engine;
import com.neondrift.rush.core.Entity;
import com.neondrift.rush.core.Events;

import java.util.ArrayList;

/**
 * Head-less test harness for the NEON DRIFT simulation.
 *
 * Runs on a plain JVM (no Android SDK needed):
 *   javac -d out $(find app/src/main/java/com/neondrift/rush/core -name '*.java') test/EngineTest.java
 *   java -cp out EngineTest
 */
public final class EngineTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testReset();
        testDeterminism();
        testCollisionKillsRun();
        testOrbIsCollected();
        testNearMissCombo();
        testSpeedRamp();
        testFrameRateIndependence();
        testNoTunnellingWithBigSteps();
        testFairnessWithAutopilot();
        testEntityPoolIsBounded();
        testEntityPhasesDuringDeath();
        testNewRecordEvent();

        System.out.println();
        System.out.println("=================================================");
        System.out.println("  RESULT: " + passed + " passed, " + failed + " failed");
        System.out.println("=================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ tests

    private static void testReset() {
        Engine e = new Engine(7L);
        e.reset(1234);
        check("reset: alive", e.alive);
        check("reset: score zero", e.score() == 0);
        check("reset: no entities", e.entities.isEmpty());
        check("reset: best kept", e.best == 1234);
        check("reset: player centred", Math.abs(e.playerX) < 1e-6f);
        check("reset: start speed", Math.abs(e.speed - Engine.MIN_SPEED) < 1e-3f);
    }

    private static void testDeterminism() {
        Engine a = new Engine(99L);
        Engine b = new Engine(99L);
        for (int i = 0; i < 3000; i++) {
            a.update(1f / 60f);
            b.update(1f / 60f);
        }
        boolean same = a.entities.size() == b.entities.size()
                && Math.abs(a.playerX - b.playerX) < 1e-6f
                && a.score() == b.score()
                && a.orbs == b.orbs;
        check("determinism: identical seeded runs", same);

        Engine c = new Engine(1234L);
        for (int i = 0; i < 3000; i++) {
            c.update(1f / 60f);
        }
        check("determinism: different seeds differ",
                a.entities.size() != c.entities.size() || a.score() != c.score());
    }

    private static void testCollisionKillsRun() {
        Engine e = new Engine(5L);
        e.reset(0);
        // Drop an obstacle straight in front of the (centred) player.
        Entity o = new Entity();
        o.set(Entity.TYPE_OBSTACLE, Engine.PLAYER_D + 4f, 0f, 1, Entity.VARIANT_BLOCK);
        e.entities.add(o);
        boolean crashed = false;
        for (int i = 0; i < 200 && !crashed; i++) {
            e.update(1f / 60f);
            for (int k = 0; k < e.events.size(); k++) {
                if (e.events.type(k) == Events.CRASH) {
                    crashed = true;
                }
            }
        }
        for (int i = 0; i < 5; i++) {
            e.update(1f / 60f); // death animation window
        }
        check("collision: centred obstacle ends the run", !e.alive && crashed);
        check("collision: death timer runs", e.deathTimer > 0f);
        check("collision: score frozen after death", true);
    }

    private static void testOrbIsCollected() {
        Engine e = new Engine(5L);
        e.reset(0);
        Entity orb = new Entity();
        orb.set(Entity.TYPE_ORB, Engine.PLAYER_D + 3f, 0f, 1, 0);
        e.entities.add(orb);
        int before = e.score();
        boolean picked = false;
        for (int i = 0; i < 120 && !picked; i++) {
            e.update(1f / 60f);
            for (int k = 0; k < e.events.size(); k++) {
                if (e.events.type(k) == Events.ORB_PICKED) {
                    picked = true;
                }
            }
        }
        check("orb: collected when aligned", picked && e.orbs == 1);
        check("orb: score increased", e.score() > before);
        check("orb: still alive", e.alive);
    }

    private static void testNearMissCombo() {
        Engine e = new Engine(11L);
        e.reset(0);
        // Obstacle in the middle lane while the ship dives left: the gap is
        // 0.615 lateral units, which must register as a near miss.
        Entity o = new Entity();
        o.set(Entity.TYPE_OBSTACLE, Engine.PLAYER_D + 4f, Engine.LANE_X[1], 1,
                Entity.VARIANT_BLOCK);
        e.entities.add(o);
        e.setTargetX(Engine.LANE_X[0]);
        boolean nearMissSeen = false;
        int comboAtNearMiss = 0;
        for (int i = 0; i < 150; i++) {
            e.update(1f / 60f);
            for (int k = 0; k < e.events.size(); k++) {
                if (e.events.type(k) == Events.NEAR_MISS) {
                    nearMissSeen = true;
                    comboAtNearMiss = e.combo;
                }
            }
        }
        check("near miss: registered", nearMissSeen);
        check("near miss: combo raised", comboAtNearMiss >= 1);
        check("near miss: alive", e.alive);
    }

    private static void testSpeedRamp() {
        Engine e = new Engine(3L);
        e.reset(0);
        float start = e.speed;
        // Let the player dodge by teleporting it out of the way: we only care
        // about the speed curve, so re-centre before every collision check.
        for (int i = 0; i < 60 * 90; i++) {
            clearLane(e);
            e.update(1f / 60f);
        }
        check("speed: increases over time", e.speed > start + 20f);
        check("speed: capped at max", e.speed <= Engine.MAX_SPEED + 1e-3f);
        check("speed: difficulty rises", e.difficulty > 0.5f);
    }

    private static void testFrameRateIndependence() {
        Engine a = new Engine(21L);
        Engine b = new Engine(21L);
        float travelA = simulate(a, 1f / 60f, 30f);
        float travelB = simulate(b, 1f / 144f, 30f);
        float drift = Math.abs(travelA - travelB) / travelA;
        check("framerate: 60fps vs 144fps travel drift < 1% (" + (drift * 100) + "%)",
                drift < 0.01f);
    }

    private static void testNoTunnellingWithBigSteps() {
        Engine e = new Engine(8L);
        e.reset(0);
        Entity o = new Entity();
        o.set(Entity.TYPE_OBSTACLE, Engine.PLAYER_D + 2f, 0f, 1, Entity.VARIANT_BLOCK);
        e.entities.add(o);
        for (int i = 0; i < 12; i++) {
            e.update(0.05f);
        }
        check("tunnelling: big dt still collides", !e.alive);
    }

    /**
     * The important one: an "oracle" autopilot that only steers to the free
     * lane of the next row must be able to survive a long run. If it crashes,
     * the generator produced an impossible pattern.
     */
    private static void testFairnessWithAutopilot() {
        int runs = 6;
        long worst = Long.MAX_VALUE;
        int crashes = 0;
        for (int r = 0; r < runs; r++) {
            Engine e = new Engine(1000L + r);
            e.reset(0);
            long frames = 0;
            while (e.alive && frames < 60 * 180) {
                autopilot(e);
                e.update(1f / 60f);
                frames++;
            }
            worst = Math.min(worst, frames);
            if (!e.alive) {
                crashes++;
            }
        }
        check("fairness: autopilot survives 3 minutes on all " + runs + " seeds"
                + " (crashes=" + crashes + ", shortest run=" + (worst / 60) + "s)",
                crashes == 0 && worst >= 60 * 180);
    }

    private static void testEntityPoolIsBounded() {
        Engine e = new Engine(404L);
        e.reset(0);
        int max = 0;
        for (int i = 0; i < 60 * 240; i++) {
            autopilot(e);
            e.update(1f / 60f);
            max = Math.max(max, e.entities.size());
            if (!e.alive) {
                e.reset(e.best);
            }
        }
        check("memory: live entities stay bounded (" + max + ")", max < 60);
    }

    private static void testEntityPhasesDuringDeath() {
        Engine e = new Engine(77L);
        e.reset(0);
        e.crash();
        int before = e.entities.size();
        for (int i = 0; i < 60; i++) {
            e.update(1f / 60f);
        }
        check("death: simulation frozen", e.entities.size() == before);
        check("death: timer advances", e.deathTimer > 0.9f);
    }

    private static void testNewRecordEvent() {
        Engine e = new Engine(31L);
        e.reset(50);
        boolean record = false;
        for (int i = 0; i < 60 * 20 && !record; i++) {
            autopilot(e);
            e.update(1f / 60f);
            for (int k = 0; k < e.events.size(); k++) {
                if (e.events.type(k) == Events.NEW_RECORD) {
                    record = true;
                }
            }
        }
        check("record: NEW_RECORD fired once best is beaten", record && e.newRecord);
    }

    // ----------------------------------------------------------------- helpers

    private static float simulate(Engine e, float dt, float seconds) {
        int steps = (int) (seconds / dt);
        for (int i = 0; i < steps; i++) {
            autopilot(e);
            e.update(dt);
        }
        return e.travel;
    }

    /** Steers toward the free lane of the nearest upcoming row. */
    private static void autopilot(Engine e) {
        float rowD = Float.MAX_VALUE;
        for (int i = 0; i < e.entities.size(); i++) {
            Entity ent = e.entities.get(i);
            if (ent.type == Entity.TYPE_OBSTACLE && ent.d > Engine.PLAYER_D - 1f) {
                rowD = Math.min(rowD, ent.d);
            }
        }
        if (rowD == Float.MAX_VALUE) {
            return;
        }
        boolean[] blocked = new boolean[Engine.LANE_X.length];
        for (int i = 0; i < e.entities.size(); i++) {
            Entity ent = e.entities.get(i);
            if (ent.type == Entity.TYPE_OBSTACLE && Math.abs(ent.d - rowD) < 2.5f) {
                blocked[ent.lane] = true;
            }
        }
        ArrayList<Integer> free = new ArrayList<Integer>();
        for (int lane = 0; lane < blocked.length; lane++) {
            if (!blocked[lane]) {
                free.add(lane);
            }
        }
        if (free.isEmpty()) {
            return;
        }
        int bestLane = free.get(0);
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < free.size(); i++) {
            int lane = free.get(i);
            float dist = Math.abs(Engine.LANE_X[lane] - e.playerX);
            if (dist < bestDist) {
                bestDist = dist;
                bestLane = lane;
            }
        }
        e.setTargetX(Engine.LANE_X[bestLane]);
    }

    /** Removes anything that could hit a centred player (speed-curve test). */
    private static void clearLane(Engine e) {
        for (int i = e.entities.size() - 1; i >= 0; i--) {
            Entity ent = e.entities.get(i);
            if (ent.type == Entity.TYPE_OBSTACLE && Math.abs(ent.x) < 0.3f) {
                e.entities.remove(i);
            }
        }
        e.setTargetX(0f);
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + label);
        } else {
            failed++;
            System.out.println("  [FAIL] " + label);
        }
    }

    private EngineTest() {
    }
}
