package com.depth3d.app.calibration

import android.content.Context
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Holds camera intrinsic parameters obtained from calibrateCamera().
 * Persisted to SharedPreferences so calibration survives app restarts.
 *
 * v1.0.2 fixes:
 *   - getDistCoeffs(): Mat shape corrected 1×5 → 5×1 (matches calibrateCamera output shape)
 *   - SharedPreferences: putFloat → putString to avoid Float precision loss on double values
 *     (fx/fy/cx/cy are ~4 digits before decimal; Float has only ~7 significant digits total)
 */
data class CalibrationData(
    val fx: Double,          // Focal length X (pixels)
    val fy: Double,          // Focal length Y (pixels)
    val cx: Double,          // Principal point X
    val cy: Double,          // Principal point Y
    val k1: Double,          // Radial distortion 1
    val k2: Double,          // Radial distortion 2
    val p1: Double,          // Tangential distortion 1
    val p2: Double,          // Tangential distortion 2
    val k3: Double,          // Radial distortion 3
    val rmsError: Double,    // Reprojection error (< 1.0 = excellent, < 2.0 = acceptable)
    val imageWidth: Int,
    val imageHeight: Int
) {
    /** Returns 3×3 camera intrinsic matrix as OpenCV Mat. Caller must release. */
    fun getCameraMatrix(): Mat {
        val mat = Mat(3, 3, CvType.CV_64F)
        mat.put(0, 0, fx, 0.0, cx)
        mat.put(1, 0, 0.0, fy, cy)
        mat.put(2, 0, 0.0, 0.0, 1.0)
        return mat
    }

    /**
     * Returns distortion coefficients as 5×1 OpenCV Mat. Caller must release.
     * Shape fix (v1.0.2): was Mat(1,5) → now Mat(5,1) to match calibrateCamera output.
     * Note: DistanceEstimator uses MatOfDouble(k1..k3) directly for type safety with solvePnP.
     */
    fun getDistCoeffs(): Mat {
        val mat = Mat(5, 1, CvType.CV_64F)
        mat.put(0, 0, k1)
        mat.put(1, 0, k2)
        mat.put(2, 0, p1)
        mat.put(3, 0, p2)
        mat.put(4, 0, k3)
        return mat
    }

    companion object {
        private const val PREFS_NAME = "depth3d_calib_v1"

        /**
         * Save calibration using String encoding to preserve full Double precision.
         * Float only has ~7 significant digits; fx/fy can be ~1000–4000, so
         * putFloat would lose sub-pixel accuracy in the fractional part.
         */
        fun save(context: Context, data: CalibrationData) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                putString("fx",  data.fx.toString())
                putString("fy",  data.fy.toString())
                putString("cx",  data.cx.toString())
                putString("cy",  data.cy.toString())
                putString("k1",  data.k1.toString())
                putString("k2",  data.k2.toString())
                putString("p1",  data.p1.toString())
                putString("p2",  data.p2.toString())
                putString("k3",  data.k3.toString())
                putString("rms", data.rmsError.toString())
                putInt("imgW", data.imageWidth)
                putInt("imgH", data.imageHeight)
                putBoolean("valid", true)
                apply()
            }
        }

        fun load(context: Context): CalibrationData? {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!p.getBoolean("valid", false)) return null
            return try {
                CalibrationData(
                    fx       = p.getString("fx",  "0")!!.toDouble(),
                    fy       = p.getString("fy",  "0")!!.toDouble(),
                    cx       = p.getString("cx",  "0")!!.toDouble(),
                    cy       = p.getString("cy",  "0")!!.toDouble(),
                    k1       = p.getString("k1",  "0")!!.toDouble(),
                    k2       = p.getString("k2",  "0")!!.toDouble(),
                    p1       = p.getString("p1",  "0")!!.toDouble(),
                    p2       = p.getString("p2",  "0")!!.toDouble(),
                    k3       = p.getString("k3",  "0")!!.toDouble(),
                    rmsError = p.getString("rms", "0")!!.toDouble(),
                    imageWidth  = p.getInt("imgW", 0),
                    imageHeight = p.getInt("imgH", 0)
                )
            } catch (e: NumberFormatException) {
                // Corrupted prefs — treat as uncalibrated
                clear(context)
                null
            }
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }
}
