package com.example.rollingdice.dice

/**
 * Static description of the die: which face carries which value, how the face
 * is oriented in the die's local space and where the pips sit.
 *
 * The layout follows a standard right handed western die, so 1, 2 and 3 run
 * counter clockwise around the corner they share.
 */
object DieModel {

    /** Edge length of the cube, in world units. */
    const val SIDE = 1.2f

    /** Distance from the centre of the die to any face. */
    const val HALF = SIDE / 2f

    /** How far a pip sits from the centre of its face. */
    const val PIP_OFFSET = HALF * 0.45f

    /** Radius of a single pip. */
    const val PIP_RADIUS = HALF * 0.21f

    /** Number of segments used to approximate a round pip. */
    const val PIP_SEGMENTS = 22

    /**
     * One face of the cube.
     *
     * @param value the number printed on the face
     * @param normal outward pointing normal in die local space
     * @param right the in-plane axis pointing to the face's right
     * @param up the in-plane axis pointing to the face's top
     */
    class Face(
        val value: Int,
        val normal: Vec3,
        val right: Vec3,
        val up: Vec3
    )

    val faces: List<Face> = listOf(
        // top / bottom
        Face(1, Vec3(0f, 1f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f)),
        Face(6, Vec3(0f, -1f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f)),
        // right / left
        Face(2, Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f), Vec3(0f, 1f, 0f)),
        Face(5, Vec3(-1f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, 1f, 0f)),
        // front / back
        Face(4, Vec3(0f, 0f, 1f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f)),
        Face(3, Vec3(0f, 0f, -1f), Vec3(-1f, 0f, 0f), Vec3(0f, 1f, 0f))
    )

    /** Pip positions on a 3x3 grid, where (0, 0) is the centre of the face. */
    private val pipLayouts: Map<Int, List<Pair<Float, Float>>> = mapOf(
        1 to listOf(0f to 0f),
        2 to listOf(-1f to 1f, 1f to -1f),
        3 to listOf(-1f to 1f, 0f to 0f, 1f to -1f),
        4 to listOf(-1f to 1f, 1f to 1f, -1f to -1f, 1f to -1f),
        5 to listOf(-1f to 1f, 1f to 1f, 0f to 0f, -1f to -1f, 1f to -1f),
        6 to listOf(
            -1f to 1f, -1f to 0f, -1f to -1f,
            1f to 1f, 1f to 0f, 1f to -1f
        )
    )

    fun pips(value: Int): List<Pair<Float, Float>> = pipLayouts.getValue(value)

    /** The eight corners of the cube in local space. */
    val corners: List<Vec3> = listOf(
        Vec3(-HALF, -HALF, -HALF), Vec3(HALF, -HALF, -HALF),
        Vec3(HALF, HALF, -HALF), Vec3(-HALF, HALF, -HALF),
        Vec3(-HALF, -HALF, HALF), Vec3(HALF, -HALF, HALF),
        Vec3(HALF, HALF, HALF), Vec3(-HALF, HALF, HALF)
    )

    /** Corner offsets of a face, in the face's own (right, up) basis. */
    val faceCorners: List<Pair<Float, Float>> = listOf(
        -1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f
    )
}
