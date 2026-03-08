package com.depth3d.app.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.depth3d.app.calibration.CalibrationManager
import com.depth3d.app.detection.DistanceEstimator
import org.opencv.core.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * CameraX ImageAnalysis.Analyzer implementation.
 *
 * Runs on a background executor — all OpenCV work stays off the main thread.
 * Communicates results back via [onResult] callback (called on analyzer thread;
 * MainActivity posts to the main thread).
 *
 * Bug fixes applied:
 *   CRITICAL-1: Removed double-release of MatOfPoint2f after captureFrame()
 *   CRITICAL-3: calibrate() no longer called while ImageProxy is still open.
 *               pendingCalibSize flag defers calibration to the NEXT analyze() call,
 *               by which time image.close() has already been called.
 */
class FrameAnalyzer(
    private val calibrationManager: CalibrationManager,
    private val distanceEstimator: DistanceEstimator,
    private val onResult: (AnalysisResult) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FrameAnalyzer"
    }

    enum class Mode { IDLE, CALIBRATING, MEASURING }

    @Volatile var mode: Mode = Mode.IDLE

    /** Set to true by UI to request one calibration capture on next frame. */
    val captureRequested = AtomicBoolean(false)

    /**
     * CRITICAL-3 fix: stores imageSize when MIN_CAPTURES is reached.
     * Non-null → next analyze() cycle runs calibrateCamera() AFTER image.close().
     */
    private val pendingCalibSize = AtomicReference<Size?>(null)

    // ─────────────────────────────────────────────────────────────────────────
    // ImageAnalysis.Analyzer
    // ─────────────────────────────────────────────────────────────────────────

    override fun analyze(image: ImageProxy) {
        // CRITICAL-3 fix: check pending calibration first, BEFORE opening a new frame.
        // At this point the PREVIOUS image is already closed, so calibrateCamera()
        // does not block any open ImageProxy.
        pendingCalibSize.getAndSet(null)?.let { size ->
            runCalibration(size)
            image.close()
            return
        }

        val grayMat = imageProxyToGray(image)
        val imageSize = Size(grayMat.cols().toDouble(), grayMat.rows().toDouble())

        try {
            when (mode) {
                Mode.CALIBRATING -> handleCalibration(grayMat, imageSize)
                Mode.MEASURING   -> handleMeasurement(grayMat)
                Mode.IDLE        -> { /* standby */ }
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyze() exception", e)
        } finally {
            grayMat.release()
            image.close()   // Always closed promptly — CameraX contract maintained
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Calibration path
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleCalibration(gray: Mat, imageSize: Size) {
        val result = calibrationManager.processFrame(gray)

        if (!result.found || result.corners == null) {
            result.corners?.release()
            onResult(AnalysisResult(
                mode = Mode.CALIBRATING,
                checkerboardFound = false,
                captureCount = calibrationManager.captureCount,
                statusMessage = "체커보드(${calibrationManager.boardCols}×${calibrationManager.boardRows})를 원 안에 맞춰주세요."
            ))
            return
        }

        // Corners found — snapshot for UI preview
        val cornersArray = result.corners.toArray()

        if (captureRequested.compareAndSet(true, false)) {
            // User pressed Capture — transfer ownership to CalibrationManager
            calibrationManager.captureFrame(result.corners, imageSize)
            // CRITICAL-1 fix: result.corners is now owned by calibrationManager.
            // DO NOT call result.corners.release() here — that would be a double-free.

            val count = calibrationManager.captureCount

            if (calibrationManager.isReadyToCalibrate()) {
                // Enough frames — defer calibrate() to next cycle (after image.close())
                // CRITICAL-3 fix: set flag, return immediately so image.close() runs first
                pendingCalibSize.set(imageSize)
                onResult(AnalysisResult(
                    mode = Mode.CALIBRATING,
                    checkerboardFound = true,
                    corners = cornersArray,
                    captureCount = count,
                    statusMessage = "캘리브레이션 계산 중… 잠시 기다려주세요."
                ))
            } else {
                onResult(AnalysisResult(
                    mode = Mode.CALIBRATING,
                    checkerboardFound = true,
                    corners = cornersArray,
                    captureCount = count,
                    statusMessage = "캡처 완료: $count / ${CalibrationManager.MIN_CAPTURES}"
                ))
            }
        } else {
            // Preview frame — corners not consumed, release properly
            onResult(AnalysisResult(
                mode = Mode.CALIBRATING,
                checkerboardFound = true,
                corners = cornersArray,
                captureCount = calibrationManager.captureCount,
                statusMessage = "감지됨! 📷 캡처 버튼을 눌러주세요. (${calibrationManager.captureCount}/${CalibrationManager.MIN_CAPTURES})"
            ))
            result.corners.release()   // Not transferred — caller must release
        }
    }

    /** Called on next analyze() cycle AFTER image.close() — safe for long computation. */
    private fun runCalibration(imageSize: Size) {
        val calResult = calibrationManager.calibrate(imageSize)
        if (calResult.success) mode = Mode.MEASURING

        onResult(AnalysisResult(
            mode = Mode.CALIBRATING,
            checkerboardFound = false,
            captureCount = calibrationManager.captureCount,
            calibrationDone = true,
            calibrationSuccess = calResult.success,
            statusMessage = calResult.message
        ))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Measurement path
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleMeasurement(gray: Mat) {
        val result = calibrationManager.processFrame(gray)

        if (!result.found || result.corners == null) {
            result.corners?.release()
            onResult(AnalysisResult(
                mode = Mode.MEASURING,
                checkerboardFound = false,
                statusMessage = "체커보드를 카메라에 보여주세요."
            ))
            return
        }

        val calibData = calibrationManager.calibrationData
        if (calibData == null) {
            result.corners.release()
            onResult(AnalysisResult(
                mode = Mode.MEASURING,
                checkerboardFound = false,
                statusMessage = "캘리브레이션 데이터 없음."
            ))
            return
        }

        val cornersArray = result.corners.toArray()
        val distResult   = distanceEstimator.estimateDistance(result.corners, calibData)
        result.corners.release()

        if (distResult.success) {
            onResult(AnalysisResult(
                mode = Mode.MEASURING,
                checkerboardFound = true,
                corners = cornersArray,
                distanceMm = distResult.distanceMm,
                distanceCm = distResult.distanceCm,
                distanceM  = distResult.distanceM,
                statusMessage = distResult.formattedString()
            ))
        } else {
            onResult(AnalysisResult(
                mode = Mode.MEASURING,
                checkerboardFound = true,
                corners = cornersArray,
                statusMessage = "거리 계산 실패 — 체커보드를 정면으로 향하게 해주세요."
            ))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // YUV_420_888 → grayscale Mat (rotation-aware)
    // ─────────────────────────────────────────────────────────────────────────

    private fun imageProxyToGray(image: ImageProxy): Mat {
        val yPlane    = image.planes[0]
        val yBuffer: ByteBuffer = yPlane.buffer.apply { rewind() }
        val rowStride = yPlane.rowStride

        val mat = Mat(image.height, image.width, CvType.CV_8UC1)

        if (rowStride == image.width) {
            // Contiguous Y-plane — single copy
            val bytes = ByteArray(yBuffer.remaining())
            yBuffer.get(bytes)
            mat.put(0, 0, bytes)
        } else {
            // Row-stride padding present — strip padding per row
            val allBytes = ByteArray(yBuffer.remaining())
            yBuffer.get(allBytes)
            val rowBytes = ByteArray(image.width)
            for (row in 0 until image.height) {
                System.arraycopy(allBytes, row * rowStride, rowBytes, 0, image.width)
                mat.put(row, 0, rowBytes)
            }
        }

        // Rotate to match display orientation (portrait = 90°)
        val rotated = Mat()
        when (image.imageInfo.rotationDegrees) {
            90  -> Core.rotate(mat, rotated, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(mat, rotated, Core.ROTATE_180)
            270 -> Core.rotate(mat, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> mat.copyTo(rotated)
        }
        mat.release()
        return rotated
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result type passed to UI
    // ─────────────────────────────────────────────────────────────────────────

    data class AnalysisResult(
        val mode: Mode,
        val checkerboardFound: Boolean  = false,
        val corners: Array<Point>?      = null,
        val distanceMm: Double          = 0.0,
        val distanceCm: Double          = 0.0,
        val distanceM:  Double          = 0.0,
        val captureCount: Int           = 0,
        val calibrationDone: Boolean    = false,
        val calibrationSuccess: Boolean = false,
        val statusMessage: String       = ""
    )
}
