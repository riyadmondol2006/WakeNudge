package com.riyadm.wakenudge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star

/** Generous corner radii: the Expressive look is round, soft and a little playful. */
internal val WakeShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(44.dp),
)

/** The expressive shape vocabulary: organic polygons that can also morph into one another. */
object Blobs {
    val Circle: RoundedPolygon by lazy { RoundedPolygon.circle(numVertices = 10).normalized() }

    /** A soft nine-lobed cookie, the signature shape for "on" states and hero badges. */
    val Cookie: RoundedPolygon by lazy {
        RoundedPolygon.star(numVerticesPerRadius = 9, innerRadius = 0.82f, rounding = CornerRounding(0.5f), innerRounding = CornerRounding(0.5f)).normalized()
    }

    /** Eight-pointed sun with rounded tips. */
    val Sunny: RoundedPolygon by lazy {
        RoundedPolygon.star(numVerticesPerRadius = 8, innerRadius = 0.78f, rounding = CornerRounding(0.2f), innerRounding = CornerRounding(0.3f)).normalized()
    }

    /** Four plump lobes. */
    val Clover: RoundedPolygon by lazy {
        RoundedPolygon.star(numVerticesPerRadius = 4, innerRadius = 0.5f, rounding = CornerRounding(0.4f), innerRounding = CornerRounding(0.4f)).normalized()
    }

    val Flower: RoundedPolygon by lazy {
        RoundedPolygon.star(numVerticesPerRadius = 6, innerRadius = 0.7f, rounding = CornerRounding(0.45f), innerRounding = CornerRounding(0.45f)).normalized()
    }

    val Burst: RoundedPolygon by lazy {
        RoundedPolygon.star(numVerticesPerRadius = 12, innerRadius = 0.85f, rounding = CornerRounding(0.15f), innerRounding = CornerRounding(0.15f)).normalized()
    }

    val Gem: RoundedPolygon by lazy { RoundedPolygon(numVertices = 6, rounding = CornerRounding(0.25f)).normalized() }

    fun morph(from: RoundedPolygon, to: RoundedPolygon): Morph = Morph(from, to)
}

private fun List<Cubic>.toComposePath(width: Float, height: Float): Path {
    val path = Path()
    if (isEmpty()) return path
    path.moveTo(first().anchor0X * width, first().anchor0Y * height)
    for (c in this) {
        path.cubicTo(
            c.control0X * width, c.control0Y * height,
            c.control1X * width, c.control1Y * height,
            c.anchor1X * width, c.anchor1Y * height,
        )
    }
    path.close()
    return path
}

/** A static polygon stretched to its container. The polygon must be [RoundedPolygon.normalized]. */
class PolygonShape(private val polygon: RoundedPolygon) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(polygon.cubics.toComposePath(size.width, size.height))
}

/** A shape between two polygons; animate [progress] with a spring for a rubbery morph. */
class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(morph.asCubics(progress.coerceIn(0f, 1f)).toComposePath(size.width, size.height))
}
