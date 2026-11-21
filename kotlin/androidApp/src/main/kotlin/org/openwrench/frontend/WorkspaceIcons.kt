package org.openwrench.frontend

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal enum class WorkspaceIcon { MENU, GRID, WAVE, SCAN, CAR, FILTER, RECORD, HISTORY, SETTINGS, WRENCH, PIN, SEARCH, PAUSE, SHIELD, CHEVRON, BOTH, FULLSCREEN, EXIT_FULLSCREEN }

@Composable
internal fun WorkspaceGlyph(index: Int, ink: Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface) =
    WorkspaceIcon(when (index) { 0 -> WorkspaceIcon.GRID; 1 -> WorkspaceIcon.WAVE;
        3 -> WorkspaceIcon.SCAN; else -> WorkspaceIcon.SETTINGS }, ink)

@Composable
internal fun WorkspaceIcon(icon: WorkspaceIcon, ink: Color = DashboardMuted, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val s = size.width
        val stroke = Stroke(s * .075f, cap = StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(ink, p(x1, y1), p(x2, y2), stroke.width, StrokeCap.Round)
        fun path(vararg points: Pair<Float, Float>) {
            val outline = Path()
            points.forEachIndexed { i, point -> if (i == 0) outline.moveTo(point.first * s, point.second * s) else outline.lineTo(point.first * s, point.second * s) }
            drawPath(outline, ink, style = stroke)
        }
        when (icon) {
            WorkspaceIcon.MENU -> { line(.1f,.2f,.9f,.2f); line(.1f,.5f,.9f,.5f); line(.1f,.8f,.9f,.8f) }
            WorkspaceIcon.GRID -> repeat(4) { cell -> drawRect(ink, p(.12f + (cell % 2) * .46f, .12f + (cell / 2) * .46f), Size(s * .27f, s * .27f), style = stroke) }
            WorkspaceIcon.WAVE -> path(.04f to .52f, .24f to .52f, .36f to .16f, .55f to .87f, .69f to .39f, .8f to .52f, .96f to .52f)
            WorkspaceIcon.SCAN -> { line(.12f,.35f,.12f,.13f); line(.12f,.13f,.34f,.13f); line(.66f,.13f,.88f,.13f); line(.88f,.13f,.88f,.35f); line(.12f,.65f,.12f,.87f); line(.12f,.87f,.34f,.87f); line(.66f,.87f,.88f,.87f); line(.88f,.87f,.88f,.65f); line(.32f,.5f,.68f,.5f) }
            WorkspaceIcon.CAR -> { path(.08f to .7f,.08f to .43f,.24f to .4f,.35f to .24f,.67f to .24f,.8f to .4f,.92f to .46f,.92f to .7f); line(.33f,.7f,.67f,.7f); drawCircle(ink,s*.1f,p(.23f,.69f),style=stroke); drawCircle(ink,s*.1f,p(.77f,.69f),style=stroke) }
            WorkspaceIcon.FILTER -> { line(.1f,.2f,.9f,.2f); line(.23f,.5f,.77f,.5f); line(.37f,.8f,.63f,.8f) }
            WorkspaceIcon.RECORD -> { drawCircle(ink,s*.39f,p(.5f,.5f),style=stroke); drawCircle(ink,s*.09f,p(.5f,.5f)) }
            WorkspaceIcon.HISTORY -> { drawArc(ink,210f,300f,false,p(.12f,.12f),Size(s*.77f,s*.77f),style=stroke); path(.05f to .15f,.05f to .43f,.31f to .43f); path(.5f to .25f,.5f to .51f,.68f to .63f) }
            WorkspaceIcon.SETTINGS -> { drawCircle(ink,s*.29f,p(.5f,.5f),style=stroke); drawCircle(ink,s*.09f,p(.5f,.5f),style=stroke); repeat(8) { i -> val angle=i*kotlin.math.PI/4; drawLine(ink,p(.5f,.5f)+Offset(kotlin.math.cos(angle).toFloat()*s*.3f,kotlin.math.sin(angle).toFloat()*s*.3f),p(.5f,.5f)+Offset(kotlin.math.cos(angle).toFloat()*s*.43f,kotlin.math.sin(angle).toFloat()*s*.43f),stroke.width) } }
            WorkspaceIcon.WRENCH -> { path(.67f to .09f,.51f to .24f,.55f to .42f,.74f to .46f,.9f to .29f,.88f to .52f,.67f to .66f,.5f to .62f,.22f to .91f,.1f to .79f,.4f to .49f,.36f to .3f,.47f to .12f,.67f to .09f) }
            WorkspaceIcon.PIN -> { path(.35f to .15f,.68f to .15f,.61f to .46f,.78f to .64f,.22f to .64f,.4f to .46f,.35f to .15f); line(.5f,.65f,.5f,.94f) }
            WorkspaceIcon.SEARCH -> { drawCircle(ink,s*.27f,p(.42f,.42f),style=stroke); line(.63f,.63f,.88f,.88f) }
            WorkspaceIcon.PAUSE -> { drawRect(ink,p(.23f,.18f),Size(s*.15f,s*.64f),style=stroke); drawRect(ink,p(.62f,.18f),Size(s*.15f,s*.64f),style=stroke) }
            WorkspaceIcon.SHIELD -> { path(.5f to .07f,.83f to .2f,.79f to .64f,.5f to .9f,.21f to .64f,.17f to .2f,.5f to .07f); path(.34f to .46f,.46f to .58f,.68f to .34f) }
            WorkspaceIcon.BOTH -> {
                path(.08f to .12f,.92f to .12f,.92f to .88f,.08f to .88f,.08f to .12f)
                line(.5f,.12f,.5f,.88f); path(.12f to .66f,.23f to .36f,.33f to .61f,.44f to .29f)
                line(.61f,.35f,.81f,.35f); line(.61f,.65f,.81f,.65f)
            }
            WorkspaceIcon.FULLSCREEN -> {
                path(.12f to .4f,.12f to .12f,.4f to .12f); path(.6f to .12f,.88f to .12f,.88f to .4f)
                path(.12f to .6f,.12f to .88f,.4f to .88f); path(.6f to .88f,.88f to .88f,.88f to .6f)
            }
            WorkspaceIcon.EXIT_FULLSCREEN -> {
                path(.12f to .4f,.4f to .4f,.4f to .12f); path(.6f to .12f,.6f to .4f,.88f to .4f)
                path(.12f to .6f,.4f to .6f,.4f to .88f); path(.6f to .88f,.6f to .6f,.88f to .6f)
            }
            WorkspaceIcon.CHEVRON -> path(.25f to .38f,.5f to .63f,.75f to .38f)
        }
    }
}
