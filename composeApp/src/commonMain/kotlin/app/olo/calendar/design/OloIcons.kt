package app.olo.calendar.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/*
 * Provisional control glyphs (24dp, single ink, tint-ready). Drawn after the OLO
 * pack's `003 일정,캘린더` (rounded sheet, two binding pins) so the five view icons
 * read as one family. They belong in OLO-Design's control_symbols.py
 * (ic_menu_view_*); move them there and delete this file once they land.
 */
object OloIcons {
    val Menu by lazy { stroke("menu") { line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f) } }
    val Search by lazy { stroke("search") { circle(10.5f, 10.5f, 6f); line(15f, 15f, 20f, 20f) } }
    val Year by lazy { calendar("year") { for (y in listOf(11f, 15f)) for (x in listOf(8f, 12f, 16f)) dot(x, y, 0.95f) } }
    val Month by lazy {
        calendar("month", extraStroke = { line(3.5f, 10f, 20.5f, 10f); line(9.2f, 10f, 9.2f, 20f); line(14.8f, 10f, 14.8f, 20f); line(3.5f, 15f, 20.5f, 15f) })
    }
    val Week by lazy { calendar("week") { roundRect(6f, 11f, 12f, 4f, 1.2f) } }
    val Day by lazy { calendar("day") { roundRect(6.5f, 10.5f, 5f, 5f, 1.2f) } }
    val Agenda by lazy {
        ImageVector.Builder("agenda", 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round) {
                line(9f, 6f, 20f, 6f); line(9f, 12f, 20f, 12f); line(9f, 18f, 20f, 18f)
            }
            path(fill = SolidColor(Color.Black)) { dot(5f, 6f, 1.4f); dot(5f, 12f, 1.4f); dot(5f, 18f, 1.4f) }
        }.build()
    }
    val Calendars by lazy {
        ImageVector.Builder("calendars", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) { roundRect(4f, 5f, 4f, 4f, 1.2f); roundRect(4f, 10f, 4f, 4f, 1.2f); roundRect(4f, 15f, 4f, 4f, 1.2f) }
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round) { line(11f, 7f, 20f, 7f); line(11f, 12f, 20f, 12f); line(11f, 17f, 20f, 17f) }
        }.build()
    }
    val Sync by lazy { stroke("sync") { moveTo(20f, 12f); arcTo(8f, 8f, 0f, true, true, 17.7f, 6.4f); moveTo(20f, 4f); lineTo(20f, 8f); lineTo(16f, 8f) } }
    val Settings by lazy {
        stroke("settings") {
            circle(12f, 12f, 3f)
            line(12f, 3f, 12f, 6f); line(12f, 18f, 12f, 21f); line(3f, 12f, 6f, 12f); line(18f, 12f, 21f, 12f)
            line(5.6f, 5.6f, 7.7f, 7.7f); line(16.3f, 16.3f, 18.4f, 18.4f); line(5.6f, 18.4f, 7.7f, 16.3f); line(16.3f, 7.7f, 18.4f, 5.6f)
        }
    }
    val Warning by lazy { stroke("warning") { moveTo(12f, 4f); lineTo(21f, 20f); lineTo(3f, 20f); close(); line(12f, 10f, 12f, 14f); line(12f, 17f, 12f, 17.4f) } }
    val Plus by lazy { stroke("plus", width = 2f) { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) } }
    val ChevronDown by lazy { stroke("chevron", width = 1.8f) { moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f) } }
    val ChevronRight by lazy { stroke("chevron-right", width = 1.9f) { moveTo(10f, 7f); lineTo(15f, 12f); lineTo(10f, 17f) } }
    val Back by lazy { stroke("back", width = 1.9f) { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) } }

    private fun stroke(name: String, width: Float = 1.8f, body: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = body)
        }.build()

    /** The shared calendar sheet with its two pins, plus a mark that names the view. */
    private fun calendar(name: String, extraStroke: (PathBuilder.() -> Unit)? = null, fillMark: (PathBuilder.() -> Unit)? = null) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                roundRect(3.5f, 5f, 17f, 15f, 3f)
                line(8f, 3f, 8f, 7f); line(16f, 3f, 16f, 7f)
                extraStroke?.invoke(this)
            }
            fillMark?.let { path(fill = SolidColor(Color.Black), pathBuilder = it) }
        }.build()

    private fun calendar(name: String, fillMark: PathBuilder.() -> Unit) = calendar(name, null, fillMark)
}

private fun PathBuilder.line(x1: Float, y1: Float, x2: Float, y2: Float) { moveTo(x1, y1); lineTo(x2, y2) }

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, false, true, cx + r, cy)
    arcTo(r, r, 0f, false, true, cx - r, cy)
    close()
}

private fun PathBuilder.dot(cx: Float, cy: Float, r: Float) = circle(cx, cy, r)

private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
    moveTo(x + r, y)
    lineTo(x + w - r, y); quadTo(x + w, y, x + w, y + r)
    lineTo(x + w, y + h - r); quadTo(x + w, y + h, x + w - r, y + h)
    lineTo(x + r, y + h); quadTo(x, y + h, x, y + h - r)
    lineTo(x, y + r); quadTo(x, y, x + r, y)
    close()
}
