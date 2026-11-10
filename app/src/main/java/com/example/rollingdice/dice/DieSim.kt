package com.example.rollingdice.dice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Rigid body simulation of a single die.
 *
 * The die is a cube of side [DieModel.SIDE] tumbling on the y = 0 floor. The
 * integrator runs on fixed sub steps so the outcome does not depend on the
 * frame rate, and the die is finally eased onto the closest flat resting pose
 * so it always ends up on a face rather than on an edge.
 */
class DieSim(private val random: Random = Random.Default) {

    enum class Phase { IDLE, LAUNCH, ROLLING, SETTLING, SETTLED }

    // Observable state, read by the renderer.
    var position by mutableStateOf(Vec3(0f, DieModel.HALF, 0f))
    var orientation by mutableStateOf(Quat.Identity)
    var value by mutableStateOf(1)
    var phase by mutableStateOf(Phase.IDLE)

    // Internal state.
    private var velocity = Vec3.Zero
    private var angularVelocity = Vec3.Zero
    private var launchFrom = Vec3.Zero
    private var launchSpin = Quat.Identity
    private var launchT = 0f
    private var settleFrom = Quat.Identity
    private var settleTo = Quat.Identity
    private var settleT = 0f
    private var settleFromPos = Vec3(0f, DieModel.HALF, 0f)
    private val settleToPos = Vec3(0f, DieModel.HALF, 0f)
    private var accumulator = 0f
    private var rollElapsed = 0f

    /** Invoked once, with the final value, every time the die comes to rest. */
    var onSettled: ((Int) -> Unit)? = null

    /** Diagnostics, mostly useful in tests. */
    val linearSpeed: Float get() = velocity.length
    val angularSpeed: Float get() = angularVelocity.length

    private val groundY = 0f

    /** True while the die is in motion and can still be re-thrown. */
    val isBusy: Boolean get() = phase != Phase.IDLE && phase != Phase.SETTLED

    // ------------------------------------------------------------------
    // Public actions
    // ------------------------------------------------------------------

    /** Throws the die into the air with a fresh random tumble. */
    fun roll() {
        if (isBusy) return

        launchFrom = position
        launchSpin = Quat.fromAxisAngle(randomAxis(), random.nextFloat() * 6.2831855f)
        launchT = 0f
        rollElapsed = 0f
        phase = Phase.LAUNCH
        velocity = Vec3.Zero
        angularVelocity = Vec3.Zero
    }

    /** Advances the simulation by [dt] seconds. */
    fun update(dt: Float) {
        when (phase) {
            Phase.LAUNCH -> updateLaunch(dt)
            Phase.ROLLING -> updateRolling(dt)
            Phase.SETTLING -> updateSettling(dt)
            else -> Unit
        }
        value = topFaceValue()
    }

    // ------------------------------------------------------------------
    // Phases
    // ------------------------------------------------------------------

    private fun updateLaunch(dt: Float) {
        launchT = min(1f, launchT + dt / LAUNCH_SECONDS)
        val t = launchT
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)

        position = Vec3(
            launchFrom.x * (1f - eased),
            DieModel.HALF + (LAUNCH_HEIGHT - DieModel.HALF) * eased,
            launchFrom.z * (1f - eased)
        )
        orientation = Quat.Identity.slerpTo(launchSpin, eased)

        if (launchT >= 1f) {
            phase = Phase.ROLLING
            velocity = Vec3(
                random.nextFloat() * 0.5f - 0.25f,
                0f,
                random.nextFloat() * 0.5f - 0.25f
            )
            angularVelocity = randomAxis() * (random.nextFloat() * 6f + 6f)
        }
    }

    private fun updateRolling(dt: Float) {
        rollElapsed += dt
        accumulator += dt
        var steps = 0
        while (accumulator >= FIXED_STEP && steps < MAX_STEPS) {
            step(FIXED_STEP)
            accumulator -= FIXED_STEP
            steps++
        }
        if (steps >= MAX_STEPS) accumulator = 0f

        val slowEnough = velocity.length < 0.18f && angularVelocity.length < 0.40f
        val outOfTime = rollElapsed > MAX_ROLL_SECONDS
        if (touchingFloor() && (slowEnough || outOfTime)) {
            beginSettle()
        }
    }

    private fun updateSettling(dt: Float) {
        settleT = min(1f, settleT + dt / SETTLE_SECONDS)
        val t = settleT
        // ease in out so the snap reads as a soft landing rather than a jump
        val eased = if (t < 0.5f) {
            4f * t * t * t
        } else {
            val f = -2f * t + 2f
            1f - f * f * f / 2f
        }
        orientation = settleFrom.slerpTo(settleTo, eased)
        position = settleFromPos.lerp(settleToPos, eased)

        if (settleT >= 1f) {
            orientation = settleTo
            position = settleToPos
            velocity = Vec3.Zero
            angularVelocity = Vec3.Zero
            phase = Phase.SETTLED
            value = topFaceValue()
            onSettled?.invoke(value)
        }
    }

    private fun beginSettle() {
        phase = Phase.SETTLING
        settleFrom = orientation
        settleTo = CubeLattice.nearest(orientation)
        settleFromPos = position
        settleT = 0f
        velocity = Vec3.Zero
        angularVelocity = Vec3.Zero
    }

    // ------------------------------------------------------------------
    // Rigid body step
    // ------------------------------------------------------------------

    private fun step(dt: Float) {
        velocity = velocity + Vec3(0f, GRAVITY * dt, 0f)
        position = position + velocity * dt

        // Continuous drag, integrated per step. Contact-only damping is
        // applied once per bounce, so on its own it barely slows a die that
        // spends most of its time in the air.
        val spinDrag = if (rollElapsed > LATE_ROLL_SECONDS) LATE_SPIN_DRAG else SPIN_DRAG
        angularVelocity = angularVelocity * max(0f, 1f - spinDrag * dt)
        val slideScale = max(0f, 1f - SLIDE_DRAG * dt)
        velocity = Vec3(velocity.x * slideScale, velocity.y, velocity.z * slideScale)

        val spin = angularVelocity.length
        if (spin > 1e-4f) {
            val delta = Quat.fromAxisAngle(angularVelocity.normalized(), spin * dt)
            orientation = (delta * orientation).normalized()
        }

        val contact = resolveFloor()

        if (contact) {
            // Energy has to go somewhere: without this the die would skitter
            // around the table forever.
            angularVelocity = angularVelocity * CONTACT_SPIN_DAMPING
            velocity = Vec3(
                velocity.x * CONTACT_SLIDE_DAMPING,
                velocity.y,
                velocity.z * CONTACT_SLIDE_DAMPING
            )
        }

        // A gentle pull keeps the die near the middle of the table. The pull
        // gets firmer further out so the die can never wander off the edge of
        // the pool of light.
        val flat = Vec3(position.x, 0f, position.z)
        val distance = flat.length
        val pull = CENTERING + CENTERING_FAR * max(0f, distance - CENTERING_ZONE)
        velocity = velocity - flat * (pull * dt)

        if (!position.isFinite() || !orientation.isFinite()) {
            resetToRest()
        }
    }

    /**
     * Resolves a contact with the floor, if there is one.
     *
     * The impulse is solved against the effective mass of the contact point
     * along the impulse direction, which includes the rotational term. Using
     * the plain mass here is what makes a tumbling die gain energy on every
     * bounce and spin off to infinity.
     */
    private fun resolveFloor(): Boolean {
        val verts = worldVertices()
        var minY = Float.MAX_VALUE
        for (v in verts) minY = min(minY, v.y)

        if (minY >= groundY) return false

        // Average of the corners actually touching the table. Flat on a face
        // that is the face centre, on an edge the middle of the edge, and on a
        // corner just the corner itself.
        var cx = 0f
        var cy = 0f
        var cz = 0f
        var count = 0
        for (v in verts) {
            if (v.y <= minY + 1e-4f) {
                cx += v.x
                cy += v.y
                cz += v.z
                count++
            }
        }
        if (count == 0) return false

        val contact = Vec3(cx / count, cy / count, cz / count)
        val lever = contact - position

        position = Vec3(position.x, position.y + (groundY - minY), position.z)

        val normal = Vec3.Up
        val pointVelocity = velocity + (angularVelocity cross lever)
        val normalSpeed = pointVelocity.y
        if (normalSpeed >= 0f) return true

        fun effectiveMass(direction: Vec3): Float {
            val arm = lever cross direction
            return INV_MASS + (arm dot arm) * INV_INERTIA
        }

        // Reverse the contact velocity, losing a bit to restitution. Slow
        // contacts are treated as inelastic: otherwise gravity alone keeps
        // re-injecting the bounce and the die rattles on an edge forever.
        val restitution = if (-normalSpeed < RESTITUTION_THRESHOLD) 0f else RESTITUTION
        val jn = -(1f + restitution) * normalSpeed / effectiveMass(normal)
        var impulse = normal * jn

        // Coulomb friction, capped so it can never reverse the slide.
        val tangential = Vec3(pointVelocity.x, 0f, pointVelocity.z)
        val tangentSpeed = tangential.length
        if (tangentSpeed > 1e-4f) {
            val direction = tangential * (1f / tangentSpeed)
            val maxFriction = FRICTION * jn
            val stopFriction = tangentSpeed / effectiveMass(direction)
            impulse = impulse - direction * min(maxFriction, stopFriction)
        }

        velocity = velocity + impulse * INV_MASS
        angularVelocity = angularVelocity + (lever cross impulse) * INV_INERTIA

        // Hard landings are the fun ones, so nudge the tumble a little. This
        // only fires well above the speed at which the die settles.
        if (-normalSpeed > 2.6f) {
            angularVelocity = angularVelocity + randomAxis() * (-normalSpeed * 0.02f)
        }

        // Safety rails in case a pathological pose ever produces a huge number.
        val spin = angularVelocity.length
        if (spin > MAX_SPIN) angularVelocity = angularVelocity * (MAX_SPIN / spin)
        val speed = velocity.length
        if (speed > MAX_SPEED) velocity = velocity * (MAX_SPEED / speed)

        return true
    }

    private fun resetToRest() {
        position = Vec3(0f, DieModel.HALF, 0f)
        orientation = CubeLattice.nearest(Quat.Identity)
        velocity = Vec3.Zero
        angularVelocity = Vec3.Zero
        phase = Phase.SETTLED
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The eight corners of the die in world space. */
    fun worldVertices(): List<Vec3> = DieModel.corners.map { local ->
        position + orientation.rotate(local)
    }

    private fun touchingFloor(): Boolean {
        val verts = worldVertices()
        var minY = Float.MAX_VALUE
        for (v in verts) minY = min(minY, v.y)
        return minY <= groundY + 0.02f
    }

    /** The number currently facing up. */
    private fun topFaceValue(): Int {
        var best = 1
        var bestY = -2f
        for (face in DieModel.faces) {
            val n = orientation.rotate(face.normal)
            if (n.y > bestY) {
                bestY = n.y
                best = face.value
            }
        }
        return best
    }

    private fun randomAxis(): Vec3 {
        val v = Vec3(
            random.nextFloat() * 2f - 1f,
            random.nextFloat() * 2f - 1f,
            random.nextFloat() * 2f - 1f
        )
        return if (v.lengthSquared < 1e-4f) Vec3(0.3f, 0.9f, 0.2f) else v.normalized()
    }

    companion object {
        private const val GRAVITY = -11.5f
        private const val RESTITUTION = 0.40f
        private const val RESTITUTION_THRESHOLD = 0.85f
        private const val FRICTION = 0.62f
        private const val FIXED_STEP = 1f / 240f
        private const val MAX_STEPS = 20
        private const val LAUNCH_SECONDS = 0.34f
        private const val SETTLE_SECONDS = 0.42f
        private const val LAUNCH_HEIGHT = 1.85f

        private const val CONTACT_SPIN_DAMPING = 0.9935f
        private const val CONTACT_SLIDE_DAMPING = 0.995f
        private const val CENTERING = 1.6f
        private const val CENTERING_FAR = 6.0f
        private const val CENTERING_ZONE = 0.30f

        /** Per second drag while the die tumbles, and the stronger late drag. */
        private const val SPIN_DRAG = 0.35f
        private const val SLIDE_DRAG = 0.25f
        private const val LATE_SPIN_DRAG = 2.2f
        private const val LATE_ROLL_SECONDS = 2.2f

        /** Guarantees the die comes to rest even if it finds a stable bounce. */
        private const val MAX_ROLL_SECONDS = 3.2f

        private const val MAX_SPIN = 30f
        private const val MAX_SPEED = 16f

        private const val INV_MASS = 1f

        /** 1 / (m * s^2 / 6) for a unit mass cube. */
        private val INV_INERTIA = 6f / (DieModel.SIDE * DieModel.SIDE)
    }
}

/** True when every component is a real number. */
private fun Vec3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

private fun Quat.isFinite(): Boolean =
    x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()
