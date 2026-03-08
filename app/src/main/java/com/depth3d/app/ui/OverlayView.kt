package com.depth3d.app.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.depth3d.app.camera.FrameAnalyzer
import com.depth3d.app.calibration.CalibrationManager
import org.opencv.core.Point as CvPoint

/**
 * Transparent overlay drawn on top of the camera PreviewView.
 *
 * v1.0.1 fixes:
 *   CRITICAL-2: Renamed local 'r' → 'res' to avoid shadowing Float param 'r'
 *   HIGH-1:     BlurMaskFilter → setShadowLayer() for HW-accelerated Canvas
 *
 * v1.0.2 fix:
 *   setShadowLayer() on text requires LAYER_TYPE_SOFTWARE on API < 28.
 *   minSdk=26, so we set software layer in init to guarantee shadow rendering
 *   on all supported devices. The overlay is lightweight (no video frames),
 *   so software rendering has negligible performance impact.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        private const val CIRCLE_RATIO     = 0.40f
        private const val CORNER_RADIUS_DP = 5f
    }

    init {
        // Required for setShadowLayer() to work on API 26-27 (hardware canvas ignores
        // shadow on text for API < 28 unless the view uses a software layer).
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    var result: FrameAnalyzer.AnalysisResult? = null
        set(value) { field = value; postInvalidate() }

    var analyzerWidth:  Int = 1280
    var analyzerHeight: Int = 960

    // ── Paints ─────────────────────────────────────────────────────────────
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 5f
        color       = Color.WHITE
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textSize  = 80f
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.DEFAULT_BOLD
        setShadowLayer(12f, 2f, 2f, Color.BLACK)
    }

    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.YELLOW
        textSize  = 38f
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }

    private val counterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.CYAN
        textSize  = 44f
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }

    private val clipPath = Path()
    private val cornerDp = context.resources.displayMetrics.density * CORNER_RADIUS_DP

    // ── Draw ────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx     = width  / 2f
        val cy     = height / 2f
        val radius = minOf(width, height) * CIRCLE_RATIO

        drawDimmedBorder(canvas, cx, cy, radius)
        drawCircleBorder(canvas, cx, cy, radius)
        drawCorners(canvas)
        drawDistanceText(canvas, cx, cy, radius)
        drawCalibCounter(canvas, cx)
        drawStatusText(canvas, cx, cy, radius)
    }

    private fun drawDimmedBorder(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        clipPath.reset()
        clipPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        clipPath.addCircle(cx, cy, r, Path.Direction.CCW)
        canvas.drawPath(clipPath, dimPaint)
    }

    private fun drawCircleBorder(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        circlePaint.color = if (result?.checkerboardFound == true) Color.GREEN else Color.WHITE
        canvas.drawCircle(cx, cy, r, circlePaint)
    }

    private fun drawCorners(canvas: Canvas) {
        val corners = result?.corners ?: return
        if (corners.isEmpty()) return

        val scaleX = width.toFloat()  / analyzerWidth.toFloat()
        val scaleY = height.toFloat() / analyzerHeight.toFloat()

        for (pt in corners) {
            canvas.drawCircle(
                pt.x.toFloat() * scaleX,
                pt.y.toFloat() * scaleY,
                cornerDp.toFloat(),
                cornerPaint
            )
        }
    }

    private fun drawDistanceText(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // CRITICAL-2 fix: 'res' (local) does not shadow 'r' (Float param)
        val res = result ?: return
        if (res.mode != FrameAnalyzer.Mode.MEASURING || !res.checkerboardFound || res.distanceCm <= 0) return

        val text  = formatDistance(res.distanceCm, res.distanceM)
        val textY = cy + r + 90f
        canvas.drawText(text, cx, textY, distancePaint)
    }

    private fun drawCalibCounter(canvas: Canvas, cx: Float) {
        val res = result ?: return
        if (res.mode != FrameAnalyzer.Mode.CALIBRATING) return
        canvas.drawText(
            "캡처: ${res.captureCount} / ${CalibrationManager.MIN_CAPTURES}",
            cx, 72f, counterPaint
        )
    }

    private fun drawStatusText(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val res = result ?: return
        if (res.statusMessage.isBlank()) return
        canvas.drawText(res.statusMessage, cx, cy + r + 150f, statusPaint)
    }

    private fun formatDistance(cm: Double, m: Double): String =
        if (cm < 100.0) "%.1f cm".format(cm) else "%.2f m".format(m)
}
