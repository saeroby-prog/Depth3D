package com.depth3d.app.calibration

import android.content.Context
import android.util.Log
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Manages camera calibration using a physical checkerboard target.
 *
 * Workflow:
 *   1. Call processFrame() each frame → returns found corners
 *   2. When user confirms good frame, call captureFrame()
 *   3. After MIN_CAPTURES, call calibrate() → saves CalibrationData
 *
 * Board spec assumed: 9×6 internal corners, 30 mm squares.
 */
class CalibrationManager(
    private val context: Context,
    val boardCols: Int = 9,          // Internal corners horizontal
    val boardRows: Int = 6,          // Internal corners vertical
    val squareSizeMm: Float = 30f    // Physical square size in mm
) {
    companion object {
        private const val TAG = "CalibrationManager"
        const val MIN_CAPTURES = 15          // Minimum frames for reliable calibration
        const val MAX_RMS_THRESHOLD = 2.0    // Reject calibrations above this RMS
    }

    private val boardSize = Size(boardCols.toDouble(), boardRows.toDouble())
    private val imagePoints = mutableListOf<Mat>()   // 2D detected corners per frame
    private val objectPoints = mutableListOf<Mat>()  // 3D world points per frame

    var captureCount: Int = 0
        private set

    var isCalibrated: Boolean = false
        private set

    var calibrationData: CalibrationData? = null
        private set

    private val cornerCriteria = TermCriteria(
        TermCriteria.EPS + TermCriteria.COUNT, 30, 0.001
    )

    // Created once — reused every frame to avoid per-frame allocation overhead (HIGH-3 fix)
    private val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))

    init {
        // Restore previously saved calibration
        calibrationData = CalibrationData.load(context)
        isCalibrated = calibrationData != null
        if (isCalibrated) {
            Log.d(TAG, "Loaded saved calibration, RMS=${calibrationData!!.rmsError}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Detect checkerboard corners in [grayMat].
     * Returns [ProcessResult] — caller must release corners when done.
     */
    fun processFrame(grayMat: Mat): ProcessResult {
        val corners = MatOfPoint2f()

        // CLAHE contrast enhancement for variable lighting (clahe instance reused from class field)
        val enhanced = Mat()
        clahe.apply(grayMat, enhanced)

        val found = Calib3d.findChessboardCorners(
            enhanced,
            boardSize,
            corners,
            Calib3d.CALIB_CB_ADAPTIVE_THRESH or
            Calib3d.CALIB_CB_NORMALIZE_IMAGE or
            Calib3d.CALIB_CB_FAST_CHECK
        )
        enhanced.release()

        if (!found || corners.rows() < boardCols * boardRows) {
            corners.release()
            return ProcessResult(found = false, corners = null)
        }

        // Sub-pixel refinement for accuracy
        Imgproc.cornerSubPix(
            grayMat, corners,
            Size(11.0, 11.0),
            Size(-1.0, -1.0),
            cornerCriteria
        )

        return ProcessResult(found = true, corners = corners)
    }

    /**
     * Adds a successfully detected frame to the calibration dataset.
     * [corners] ownership transfers here — do not release externally after calling.
     */
    fun captureFrame(corners: MatOfPoint2f, imageSize: Size) {
        imagePoints.add(corners)                 // Takes ownership
        objectPoints.add(buildObjectPoints())
        captureCount++
        Log.d(TAG, "Captured $captureCount / $MIN_CAPTURES  imageSize=$imageSize")
    }

    /** Returns true when enough captures have been taken to attempt calibration. */
    fun isReadyToCalibrate(): Boolean = captureCount >= MIN_CAPTURES

    /**
     * Runs calibrateCamera() on collected frames.
     * Should be called off the main thread.
     */
    fun calibrate(imageSize: Size): CalibrationResult {
        if (!isReadyToCalibrate()) {
            return CalibrationResult(false, 0.0,
                "캡처 부족: $captureCount / $MIN_CAPTURES")
        }

        val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
        // CRITICAL-4 fix: 5 coefficients (k1,k2,p1,p2,k3) — standard model, no RATIONAL_MODEL flag
        val distCoeffs   = Mat.zeros(5, 1, CvType.CV_64F)
        val rvecs        = mutableListOf<Mat>()
        val tvecs        = mutableListOf<Mat>()

        return try {
            val rms = Calib3d.calibrateCamera(
                objectPoints,
                imagePoints,
                imageSize,
                cameraMatrix,
                distCoeffs,
                rvecs,
                tvecs
            )
            Log.d(TAG, "RMS=$rms  fx=${cameraMatrix.get(0,0)[0]}")

            if (rms > MAX_RMS_THRESHOLD) {
                return CalibrationResult(false, rms,
                    "RMS 오차 과대 (%.3f). 다시 시도하세요.".format(rms))
            }

            val data = CalibrationData(
                fx          = cameraMatrix.get(0, 0)[0],
                fy          = cameraMatrix.get(1, 1)[0],
                cx          = cameraMatrix.get(0, 2)[0],
                cy          = cameraMatrix.get(1, 2)[0],
                k1          = distCoeffs.get(0, 0)[0],
                k2          = distCoeffs.get(1, 0)[0],
                p1          = distCoeffs.get(2, 0)[0],
                p2          = distCoeffs.get(3, 0)[0],
                k3          = distCoeffs.get(4, 0)[0],   // rows=5, index 4 always valid
                rmsError    = rms,
                imageWidth  = imageSize.width.toInt(),
                imageHeight = imageSize.height.toInt()
            )
            CalibrationData.save(context, data)
            calibrationData = data
            isCalibrated    = true

            CalibrationResult(true, rms,
                "✅ 캘리브레이션 성공! (RMS: %.3f)".format(rms))
        } catch (e: Exception) {
            Log.e(TAG, "calibrateCamera failed", e)
            CalibrationResult(false, 0.0, "오류: ${e.message}")
        } finally {
            cameraMatrix.release()
            distCoeffs.release()
            rvecs.forEach { it.release() }
            tvecs.forEach { it.release() }
        }
    }

    /** Clears in-memory capture buffer (keeps saved calibration). */
    fun resetCaptures() {
        imagePoints.forEach { it.release() }
        objectPoints.forEach { it.release() }
        imagePoints.clear()
        objectPoints.clear()
        captureCount = 0
    }

    /** Clears captures AND deletes saved calibration data. */
    fun resetAll() {
        resetCaptures()
        CalibrationData.clear(context)
        calibrationData = null
        isCalibrated    = false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Builds the 3D reference points for one checkerboard view (Z=0 plane). */
    private fun buildObjectPoints(): MatOfPoint3f {
        val pts = MatOfPoint3f()
        val arr = Array(boardCols * boardRows) { i ->
            Point3(
                (i % boardCols) * squareSizeMm.toDouble(),
                (i / boardCols) * squareSizeMm.toDouble(),
                0.0
            )
        }
        pts.fromArray(*arr)
        return pts
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result types
    // ─────────────────────────────────────────────────────────────────────────

    data class ProcessResult(
        val found: Boolean,
        val corners: MatOfPoint2f?   // null when found=false
    )

    data class CalibrationResult(
        val success: Boolean,
        val rmsError: Double,
        val message: String
    )
}
