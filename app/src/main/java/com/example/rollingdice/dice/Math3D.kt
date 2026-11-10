package com.example.rollingdice.dice

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tiny hand rolled 3D math kit.
 *
 * Everything the renderer needs (vectors, quaternions, the 24 rotations a cube
 * can rest in) lives here so the drawing code stays free of bookkeeping.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )

    val length: Float get() = sqrt(x * x + y * y + z * z)
    val lengthSquared: Float get() = x * x + y * y + z * z

    fun normalized(): Vec3 {
        val l = length
        return if (l < 1e-7f) Vec3(0f, 1f, 0f) else Vec3(x / l, y / l, z / l)
    }

    fun lerp(o: Vec3, t: Float) =
        Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)

    companion object {
        val Zero = Vec3(0f, 0f, 0f)
        val Up = Vec3(0f, 1f, 0f)
    }
}

/** Unit quaternion, stored as (x, y, z, w). */
data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {

    infix fun dot(o: Quat): Float = x * o.x + y * o.y + z * o.z + w * o.w

    operator fun times(o: Quat) = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z
    )

    fun normalized(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l < 1e-7f) Identity else Quat(x / l, y / l, z / l, w / l)
    }

    /** Rotates [v] by this quaternion. */
    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val uuv = u dot u
        val uvv = u dot v
        return u * (2f * uvv) +
            v * (w * w - uuv) +
            (u cross v) * (2f * w)
    }

    /** Shortest path interpolation, safe for small angles. */
    fun slerpTo(target: Quat, t: Float): Quat {
        var b = target
        var cosom = this dot target
        if (cosom < 0f) {
            b = Quat(-b.x, -b.y, -b.z, -b.w)
            cosom = -cosom
        }
        if (cosom > 0.9995f) {
            return Quat(
                x + (b.x - x) * t,
                y + (b.y - y) * t,
                z + (b.z - z) * t,
                w + (b.w - w) * t
            ).normalized()
        }
        val omega = acos(cosom.coerceIn(-1f, 1f))
        val sinOmega = sin(omega)
        val s0 = sin((1f - t) * omega) / sinOmega
        val s1 = sin(t * omega) / sinOmega
        return Quat(
            x * s0 + b.x * s1,
            y * s0 + b.y * s1,
            z * s0 + b.z * s1,
            w * s0 + b.w * s1
        ).normalized()
    }

    companion object {
        val Identity = Quat(0f, 0f, 0f, 1f)

        fun fromAxisAngle(axis: Vec3, radians: Float): Quat {
            val a = axis.normalized()
            val h = radians * 0.5f
            val s = sin(h)
            return Quat(a.x * s, a.y * s, a.z * s, cos(h))
        }

        fun fromEuler(pitch: Float, yaw: Float, roll: Float): Quat =
            fromAxisAngle(Vec3(1f, 0f, 0f), pitch) *
                fromAxisAngle(Vec3(0f, 1f, 0f), yaw) *
                fromAxisAngle(Vec3(0f, 0f, 1f), roll)
    }
}

/**
 * The 24 orientations a cube can rest in. Used to snap a tumbling die onto a
 * perfectly flat face once it has come to a stop.
 */
object CubeLattice {

    private val quaternions: List<Quat> = buildList {
        add(Quat.Identity)

        val axes = listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f))

        // 90 and 270 degrees around the three face axes
        for (axis in axes) {
            add(Quat.fromAxisAngle(axis, 1.5707964f))
            add(Quat.fromAxisAngle(axis, -1.5707964f))
        }
        // 180 degrees around the three face axes
        for (axis in axes) add(Quat.fromAxisAngle(axis, 3.1415927f))

        // 120 and 240 degrees around the four body diagonals
        for (sx in listOf(1f, -1f)) {
            for (sy in listOf(1f, -1f)) {
                for (sz in listOf(1f, -1f)) {
                    val axis = Vec3(sx, sy, sz).normalized()
                    add(Quat.fromAxisAngle(axis, 2.0943952f))
                    add(Quat.fromAxisAngle(axis, -2.0943952f))
                }
            }
        }
        // 180 degrees around the six edge axes
        val edges = listOf(
            Vec3(1f, 1f, 0f), Vec3(1f, -1f, 0f),
            Vec3(1f, 0f, 1f), Vec3(1f, 0f, -1f),
            Vec3(0f, 1f, 1f), Vec3(0f, 1f, -1f)
        )
        for (edge in edges) add(Quat.fromAxisAngle(edge.normalized(), 3.1415927f))
    }

    /** The lattice orientation closest to [q], expressed as a rotation of the die. */
    fun nearest(q: Quat): Quat {
        var best = quaternions.first()
        var bestDot = -1f
        for (candidate in quaternions) {
            val d = abs(q dot candidate)
            if (d > bestDot) {
                bestDot = d
                best = candidate
            }
        }
        return if ((q dot best) < 0f) Quat(-best.x, -best.y, -best.z, -best.w) else best
    }
}
