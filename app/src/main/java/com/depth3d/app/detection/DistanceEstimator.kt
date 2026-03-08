package com.depth3d.app.detection

import android.util.Log
import com.depth3d.app.calibration.CalibrationData
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Estimates distance to a detected checkerboard using two methods:
 *
 *   1. solvePnP  — full 6-DoF pose estimation, most accurate (primary)
 *   2. Pinhole   — Z = (fx * W_real) / W_pixel, fast fallback / cross-check
 *
 * OpenCV 4.9.0 Maven solvePnP signature:
 *   solvePnP(MatOfPoint3f, MatOfPoint2f, Mat, MatOfDouble, MatOfDouble, MatOfDouble, ...)
 *   → cameraMatrix : Mat        (OK)
 *   → distCoeffs   : MatOfDouble (was Mat → TYPE MISMATCH)
 *   → rvec         : MatOfDouble (was Mat → TYPE MISMATCH)
 *   → tvec         : MatOfDouble (was Mat → TYPE MISMATCH)
 */
class DistanceEstimator(
    private val boardCols: Int      = 9,
    private val boardRows: Int      = 6,
    private val squareSizeMm: Float = 30f
) {
    companion object {
        private const val TAG = "DistanceEstimator"
        const val MIN_VALID_DIST_MM = 50.0
        const val MAX_VALID_DIST_MM = 5000.0
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Primary: solvePnP
    // ─────────────────────────────────────────────────────────────────────────

    fun estimateDistance(
        corners: MatOfPoint2f,
        calibData: CalibrationData
    ): DistanceResult {
        val objectPoints = buildObjectPoints()
        val cameraMatrix = calibData.getCameraMatrix()   // returns Mat — OK

        // Build MatOfDouble directly from calibration values
        // — avoids Mat→MatOfDouble conversion and type mismatch
        val distCoeffs = MatOfDouble(
            calibData.k1, calibData.k2,
            calibData.p1, calibData.p2,
            calibData.k3
        )
        val rvec = MatOfDouble()
        val tvec = MatOfDouble()

        return try {
            val solved = Calib3d.solvePnP(
                objectPoints,
                corners,
                cameraMatrix,
                distCoeffs,
                rvec,
                tvec,
                false,
                Calib3d.SOLVEPNP_ITERATIVE
            )

            if (!solved) return DistanceResult(false)

            // tvec.toArray() → DoubleArray [tx, ty, tz] in mm
            val tvecArr = tvec.toArray()
            if (tvecArr.size < 3) return DistanceResult(false)
            val tz = tvecArr[2]

            Log.v(TAG, "solvePnP tvec=[%.1f, %.1f, %.1f]".format(
                tvecArr[0], tvecArr[1], tz))

            if (tz < MIN_VALID_DIST_MM || tz > MAX_VALID_DIST_MM) {
                return DistanceResult(false)
            }

            DistanceResult(
                success    = true,
                distanceMm = tz,
                distanceCm = tz / 10.0,
                distanceM  = tz / 1000.0,
                method     = "PnP"
            )
        } catch (e: Exception) {
            Log.e(TAG, "solvePnP exception", e)
            DistanceResult(false)
        } finally {
            objectPoints.release()
            cameraMatrix.release()
            distCoeffs.release()
            rvec.release()
            tvec.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fallback: Pinhole  Z = (fx * W_real) / W_pixel
    // ─────────────────────────────────────────────────────────────────────────

    fun pinholeEstimate(
        corners: MatOfPoint2f,
        calibData: CalibrationData
    ): DistanceResult {
        val pts = corners.toArray()
        if (pts.size < 2) return DistanceResult(false)

        val dx = pts[1].x - pts[0].x
        val dy = pts[1].y - pts[0].y
        val pixelWidth = sqrt(dx.pow(2) + dy.pow(2))
        if (pixelWidth < 1.0) return DistanceResult(false)

        val zMm = (calibData.fx * squareSizeMm) / pixelWidth

        return if (zMm in MIN_VALID_DIST_MM..MAX_VALID_DIST_MM) {
            DistanceResult(true, zMm, zMm / 10.0, zMm / 1000.0, "Pinhole")
        } else {
            DistanceResult(false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

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
    // Result type
    // ─────────────────────────────────────────────────────────────────────────

    data class DistanceResult(
        val success:    Boolean,
        val distanceMm: Double = 0.0,
        val distanceCm: Double = 0.0,
        val distanceM:  Double = 0.0,
        val method:     String = ""
    ) {
        fun formattedString(): String = when {
            !success         -> "—"
            distanceCm < 100 -> "%.1f cm".format(distanceCm)
            else             -> "%.2f m".format(distanceM)
        }
    }
}
