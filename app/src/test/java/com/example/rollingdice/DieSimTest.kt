package com.example.rollingdice

import com.example.rollingdice.dice.DieModel
import com.example.rollingdice.dice.DieSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Runs the rigid body simulation headlessly and checks that the die always
 * comes to rest on a face without the state blowing up.
 */
class DieSimTest {

    private fun isFinite(sim: DieSim): Boolean {
        val q = sim.orientation
        val p = sim.position
        return q.x.isFinite() && q.y.isFinite() && q.z.isFinite() && q.w.isFinite() &&
            p.x.isFinite() && p.y.isFinite() && p.z.isFinite()
    }

    @Test
    fun dieAlwaysSettlesFlatAndFinite() {
        val sim = DieSim()
        val results = ArrayList<Int>()
        sim.onSettled = { results.add(it) }

        var maxAngular = 0f
        var maxLinear = 0f
        var maxRestY = 0f
        var maxRoam = 0f

        repeat(60) { rollIndex ->
            sim.roll()
            var t = 0f
            var guard = 0
            while (!(sim.phase == DieSim.Phase.SETTLED && t > 0.2f) && guard < 60 * 30) {
                sim.update(1f / 60f)
                t += 1f / 60f
                guard++
                maxAngular = maxOf(maxAngular, sim.angularSpeed)
                maxLinear = maxOf(maxLinear, sim.linearSpeed)
                maxRoam = maxOf(maxRoam, kotlin.math.hypot(sim.position.x, sim.position.z))
                if (!isFinite(sim)) {
                    throw AssertionError(
                        "state went non finite on roll $rollIndex at t=$t " +
                            "pos=${sim.position} q=${sim.orientation}"
                    )
                }
            }
            assertTrue(
                "roll $rollIndex never settled (phase=${sim.phase}, t=$t)",
                sim.phase == DieSim.Phase.SETTLED
            )

            // Resting pose must be exactly flat on a face.
            assertEquals(
                "roll $rollIndex rests at wrong height",
                DieModel.HALF.toDouble(),
                sim.position.y.toDouble(),
                1e-3
            )
            // Every corner should sit on or above the floor and the lowest
            // corners should all be on it.
            val verts = sim.worldVertices()
            val minY = verts.minOf { it.y }
            assertEquals("roll $rollIndex lowest corner off floor", 0.0, minY.toDouble(), 1e-2)
            val onFloor = verts.count { abs(it.y) < 1e-2 }
            assertEquals("roll $rollIndex not resting on a full face", 4, onFloor)

            // The recorded value must match the face pointing up.
            val top = DieModel.faces
                .maxByOrNull { sim.orientation.rotate(it.normal).y }!!
            assertEquals("roll $rollIndex value mismatch", top.value, sim.value)
            maxRestY = maxOf(maxRestY, abs(sim.position.x), abs(sim.position.z))
        }

        println("results=${results}")
        println("distinct=${results.toSet().sorted()}")
        println("maxAngular=$maxAngular maxLinear=$maxLinear maxDrift=$maxRestY maxRoam=$maxRoam")
        assertEquals("expected 60 recorded results", 60, results.size)
        assertTrue("die should stay near the table, drifted to $maxRestY", maxRestY < 3.5f)
        assertTrue("die should not wander off the table, roamed to $maxRoam", maxRoam < 1.3f)
    }
}
