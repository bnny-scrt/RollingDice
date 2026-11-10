package com.example.rollingdice.dice

import androidx.compose.ui.geometry.Offset

/**
 * Camera placement for the scene.
 *
 * The camera sits off to the right and slightly above so that three faces of
 * the die are visible at the same time. Straight on would flatten the cube
 * into a square and lose the sense of depth.
 */
object DiceCamera {
    val Position = Vec3(3.05f, 3.62f, 4.55f)
    val Target = Vec3(0f, 0.62f, 0f)

    /** Focal length relative to the shorter side of the canvas. */
    const val FocalFactor = 1.85f

    /**
     * How much the camera follows the die across the table.
     *
     * A thrown die skitters around, so the camera drifts along with it. It
     * never follows all the way: the die still visibly moves across the
     * table, but it can never wander out of frame.
     */
    const val Tracking = 0.7f
}

/**
 * A pinhole camera that turns world space points into canvas pixels.
 */
class Projector(
    val cameraPosition: Vec3,
    target: Vec3,
    val focal: Float,
    val centerX: Float,
    val centerY: Float
) {
    private val forward = (target - cameraPosition).normalized()
    private val right = forward.cross(Vec3.Up).normalized()
    private val up = right.cross(forward)

    /** Depth of [p] along the view axis. Larger means further away. */
    fun depth(p: Vec3): Float {
        val d = p - cameraPosition
        return d dot forward
    }

    /** Projects [p], or returns null when it sits behind the camera. */
    fun project(p: Vec3): Offset? {
        val d = p - cameraPosition
        val z = d dot forward
        if (z <= 0.05f) return null
        val x = d dot right
        val y = d dot up
        return Offset(centerX + focal * x / z, centerY - focal * y / z)
    }
}
