package com.example.rollingdice.dice

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A face of the die that survived back face culling, ready to be drawn. */
private class RenderedFace(
    val face: DieModel.Face,
    val normal: Vec3,
    val center: Vec3,
    val corners: List<Offset>,
    val depth: Float
)

/**
 * Draws the die with a simple perspective camera, one directional light and a
 * soft cast shadow. Everything is monochrome: white paper, ink black lines and
 * grey shading.
 */
object DieScene {

    val Paper = Color(0xFFFFFFFF)
    val Ink = Color(0xFF101010)
    val Pip = Color(0xFF161616)
    val Floor = Color(0xFFEDEDED)

    /** Direction the light travels towards, from upper left and slightly front. */
    val LightDir = Vec3(-0.34f, 0.80f, 0.50f).normalized()
}

private const val AMBIENT = 0.58f
private const val DIFFUSE = 0.42f

/** How much darker the shaded end of a face gradient gets. */
private const val GRADIENT_FALLOFF = 0.78f

/**
 * Renders the complete scene: backdrop, shadows and the die itself.
 */
fun DrawScope.drawDieScene(sim: DieSim) {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) return

    drawRect(DieScene.Paper)

    val focal = min(width, height) * DiceCamera.FocalFactor

    // The camera drifts along with the die so that a die which skitters
    // across the table can never leave the frame.
    val shift = Vec3(
        sim.position.x * DiceCamera.Tracking,
        0f,
        sim.position.z * DiceCamera.Tracking
    )
    val projector = Projector(
        cameraPosition = DiceCamera.Position + shift,
        target = DiceCamera.Target + shift,
        focal = focal,
        centerX = width / 2f,
        centerY = height / 2f
    )

    drawFloorPool(projector, width, height)
    drawAmbientShadow(sim, projector)
    drawCastShadow(sim, projector)
    drawDie(sim, projector)
}

/** A soft pool of light on the table so the die does not float in a void. */
private fun DrawScope.drawFloorPool(
    projector: Projector,
    width: Float,
    height: Float
) {
    val center = projector.project(Vec3(0f, 0f, 0f)) ?: return
    val radius = min(width, height) * 0.85f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(DieScene.Floor, DieScene.Floor.copy(alpha = 0f)),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/** The tight, soft shadow right underneath the die. */
private fun DrawScope.drawAmbientShadow(sim: DieSim, projector: Projector) {
    val base = Vec3(sim.position.x, 0f, sim.position.z)
    val spread = DieModel.HALF * 1.5f
    val points = ArrayList<Offset>(24)
    for (i in 0 until 24) {
        val a = i * 6.2831855f / 24f
        val p = Vec3(
            base.x + cos(a) * spread,
            0f,
            base.z + sin(a) * spread
        )
        val o = projector.project(p) ?: continue
        points.add(o)
    }
    if (points.size < 3) return

    val path = Path()
    points.forEachIndexed { index, o ->
        if (index == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
    }
    path.close()

    val center = projector.project(base) ?: return
    var radius = 0f
    for (o in points) radius = max(radius, (o - center).getDistance())

    // The higher the die sits, the wider and fainter the shadow becomes.
    val height = sim.position.y
    val lift = (1f / (1f + height * 0.85f)).coerceIn(0.22f, 1f)

    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.26f * lift),
                Color.Black.copy(alpha = 0.10f * lift),
                Color.Black.copy(alpha = 0f)
            ),
            center = center,
            radius = radius
        )
    )
}

/** The crisp shadow the die throws across the table. */
private fun DrawScope.drawCastShadow(sim: DieSim, projector: Projector) {
    val points = ArrayList<Offset>()
    for (v in sim.worldVertices()) {
        val t = v.y / DieScene.LightDir.y
        val onFloor = v - DieScene.LightDir * t
        val o = projector.project(onFloor) ?: continue
        points.add(o)
    }
    if (points.size < 3) return

    val hull = convexHull(points)
    if (hull.size < 3) return

    var cx = 0f
    var cy = 0f
    for (o in hull) {
        cx += o.x
        cy += o.y
    }
    cx /= hull.size
    cy /= hull.size

    val lift = (1f / (1f + sim.position.y * 0.7f)).coerceIn(0.2f, 1f)

    // Three stacked copies give the shadow a soft edge without a blur filter.
    val layers = listOf(1.09f to 0.035f, 1.045f to 0.055f, 1f to 0.085f)
    for ((scale, alpha) in layers) {
        val path = Path()
        hull.forEachIndexed { index, o ->
            val x = cx + (o.x - cx) * scale
            val y = cy + (o.y - cy) * scale
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, Color.Black.copy(alpha = alpha * lift))
    }
}

/**
 * Draws the die: every visible face is filled with a lit gradient, its pips are
 * stamped on and the edges are inked in.
 */
private fun DrawScope.drawDie(sim: DieSim, projector: Projector) {
    val q = sim.orientation
    val pos = sim.position

    val rendered = ArrayList<RenderedFace>(6)
    for (face in DieModel.faces) {
        val normal = q.rotate(face.normal)
        val center = pos + q.rotate(face.normal * DieModel.HALF)

        // Back face culling.
        if ((normal dot (projector.cameraPosition - center)) <= 0f) continue

        val corners = ArrayList<Offset>(4)
        var visible = true
        for ((su, sv) in DieModel.faceCorners) {
            val local = face.normal * DieModel.HALF +
                face.right * (su * DieModel.HALF) +
                face.up * (sv * DieModel.HALF)
            val screen = projector.project(pos + q.rotate(local))
            if (screen == null) {
                visible = false
                break
            }
            corners.add(screen)
        }
        if (!visible) continue
        rendered.add(RenderedFace(face, normal, center, corners, projector.depth(center)))
    }

    // Painter's algorithm: the far faces go down first.
    rendered.sortBy { it.depth }

    val outline = Stroke(width = 1.9.dp.toPx())

    for (r in rendered) {
        val path = Path()
        r.corners.forEachIndexed { index, o ->
            if (index == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        path.close()

        drawPath(path, faceBrush(r, projector))

        drawPips(r, q, pos, projector)

        drawPath(path, DieScene.Ink, style = outline)
    }
}

/** Lambert shading plus a soft gradient across the face. */
private fun DrawScope.faceBrush(r: RenderedFace, projector: Projector): Brush {
    val lit = max(0f, r.normal dot DieScene.LightDir)
    val shade = AMBIENT + DIFFUSE * lit

    // Direction of the light inside the plane of the face.
    val along = DieScene.LightDir - r.normal * (DieScene.LightDir dot r.normal)
    val bright = grey(shade)
    val dark = grey(shade * GRADIENT_FALLOFF)

    if (along.lengthSquared < 1e-5f) return Brush.verticalGradient(listOf(bright, dark))

    val step = DieModel.HALF * 0.85f
    val from = projector.project(r.center + along.normalized() * step)
    val to = projector.project(r.center - along.normalized() * step)
    if (from == null || to == null) return Brush.verticalGradient(listOf(bright, dark))

    return Brush.linearGradient(
        colors = listOf(bright, dark),
        start = from,
        end = to
    )
}

private fun grey(shade: Float): Color {
    val v = (shade * 255f).toInt().coerceIn(0, 255)
    return Color(v, v, v)
}

/** Stamps the pips of a face onto the canvas. */
private fun DrawScope.drawPips(
    r: RenderedFace,
    q: Quat,
    pos: Vec3,
    projector: Projector
) {
    val face = r.face
    for ((gx, gy) in DieModel.pips(face.value)) {
        val local = face.normal * DieModel.HALF +
            face.right * (gx * DieModel.PIP_OFFSET) +
            face.up * (gy * DieModel.PIP_OFFSET)

        val path = Path()
        var started = false
        for (i in 0..DieModel.PIP_SEGMENTS) {
            val a = i * 6.2831855f / DieModel.PIP_SEGMENTS
            val p = local +
                face.right * (cos(a) * DieModel.PIP_RADIUS) +
                face.up * (sin(a) * DieModel.PIP_RADIUS)
            val screen = projector.project(pos + q.rotate(p)) ?: continue
            if (!started) {
                path.moveTo(screen.x, screen.y)
                started = true
            } else {
                path.lineTo(screen.x, screen.y)
            }
        }
        if (!started) continue
        path.close()
        drawPath(path, DieScene.Pip)
    }
}

/** Andrew's monotone chain, used for the outline of the cast shadow. */
private fun convexHull(points: List<Offset>): List<Offset> {
    if (points.size < 3) return points
    val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
    val lower = ArrayList<Offset>()
    for (p in sorted) {
        while (lower.size >= 2 && turn(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
            lower.removeAt(lower.size - 1)
        }
        lower.add(p)
    }
    val upper = ArrayList<Offset>()
    for (p in sorted.asReversed()) {
        while (upper.size >= 2 && turn(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
            upper.removeAt(upper.size - 1)
        }
        upper.add(p)
    }
    lower.removeAt(lower.size - 1)
    upper.removeAt(upper.size - 1)
    return lower + upper
}

private fun turn(o: Offset, a: Offset, b: Offset): Float =
    (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
