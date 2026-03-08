package com.depth3d.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.depth3d.app.calibration.CalibrationManager
import com.depth3d.app.camera.FrameAnalyzer
import com.depth3d.app.detection.DistanceEstimator
import com.depth3d.app.ui.OverlayView
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val ANALYZER_WIDTH  = 1280
        private const val ANALYZER_HEIGHT = 960
    }

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var previewView:         PreviewView
    private lateinit var overlayView:         OverlayView
    private lateinit var tvStatus:            TextView
    private lateinit var tvCalibInfo:         TextView
    private lateinit var btnStartCalibration: Button
    private lateinit var btnCapture:          Button
    private lateinit var btnFinishCalib:      Button
    private lateinit var btnMeasure:          Button
    private lateinit var btnReset:            Button

    // ── Core modules ───────────────────────────────────────────────────────
    private lateinit var cameraExecutor:      ExecutorService
    private lateinit var calibrationManager: CalibrationManager
    private lateinit var distanceEstimator:  DistanceEstimator
    private lateinit var frameAnalyzer:      FrameAnalyzer

    // ── Permission launcher ────────────────────────────────────────────────
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) bindCamera()
            else {
                Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. OpenCV init (must come first before any Mat usage)
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV initialization failed")
            AlertDialog.Builder(this)
                .setTitle("초기화 오류")
                .setMessage("OpenCV를 초기화하지 못했습니다. 앱을 다시 시작해 주세요.")
                .setPositiveButton("확인") { _, _ -> finish() }
                .show()
            return
        }
        Log.i(TAG, "OpenCV initialized OK")

        // 2. Views
        bindViews()
        setupButtons()

        // 3. Modules
        cameraExecutor      = Executors.newSingleThreadExecutor()
        calibrationManager  = CalibrationManager(this)
        distanceEstimator   = DistanceEstimator()
        frameAnalyzer       = FrameAnalyzer(
            calibrationManager = calibrationManager,
            distanceEstimator  = distanceEstimator,
            onResult           = ::handleAnalysisResult
        )

        // Inform OverlayView of analyzer resolution for coordinate mapping
        overlayView.analyzerWidth  = ANALYZER_WIDTH
        overlayView.analyzerHeight = ANALYZER_HEIGHT

        // Auto-resume measuring if calibration was already done
        if (calibrationManager.isCalibrated) {
            frameAnalyzer.mode = FrameAnalyzer.Mode.MEASURING
        }

        updateUI()

        // 4. Camera
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CameraX binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder()
                .build()
                .apply { setSurfaceProvider(previewView.surfaceProvider) }

            // MEDIUM-1 fix: ResolutionSelector replaces deprecated setTargetResolution()
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(ANALYZER_WIDTH, ANALYZER_HEIGHT),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .apply { setAnalyzer(cameraExecutor, frameAnalyzer) }

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                // Continuous auto-focus on center
                val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                val afPoint = factory.createPoint(0.5f, 0.5f)
                camera.cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(afPoint,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                        .build()
                )
                Log.i(TAG, "Camera bound OK")

            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                runOnUiThread {
                    Toast.makeText(this, "카메라 연결 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Analysis result handler  (called on analyzer thread → post to UI thread)
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleAnalysisResult(result: FrameAnalyzer.AnalysisResult) {
        runOnUiThread {
            overlayView.result = result
            tvStatus.text      = result.statusMessage

            if (result.calibrationDone) {
                if (result.calibrationSuccess) {
                    Toast.makeText(this, "✅ 캘리브레이션 성공!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "⚠️ ${result.statusMessage}", Toast.LENGTH_LONG).show()
                    // Stay in calibration mode so user can retry
                    frameAnalyzer.mode = FrameAnalyzer.Mode.CALIBRATING
                }
                updateUI()
            }

            // Update calibration info panel
            calibrationManager.calibrationData?.let { d ->
                tvCalibInfo.text = "fx=%.1f  RMS=%.3f".format(d.fx, d.rmsError)
                tvCalibInfo.visibility = View.VISIBLE
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Button actions
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnStartCalibration.setOnClickListener {
            calibrationManager.resetCaptures()
            frameAnalyzer.mode = FrameAnalyzer.Mode.CALIBRATING
            updateUI()
            tvStatus.text = "체커보드를 원 안에 맞춰주세요. 📷 캡처 버튼으로 수집하세요."
        }

        btnCapture.setOnClickListener {
            frameAnalyzer.captureRequested.set(true)
        }

        btnFinishCalib.setOnClickListener {
            if (!calibrationManager.isReadyToCalibrate()) {
                Toast.makeText(this,
                    "아직 ${CalibrationManager.MIN_CAPTURES - calibrationManager.captureCount}장 더 필요합니다.",
                    Toast.LENGTH_SHORT).show()
            }
            // Calibration is triggered automatically in FrameAnalyzer once MIN_CAPTURES is reached
        }

        btnMeasure.setOnClickListener {
            if (!calibrationManager.isCalibrated) {
                Toast.makeText(this, "먼저 캘리브레이션을 완료하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            frameAnalyzer.mode = FrameAnalyzer.Mode.MEASURING
            updateUI()
            tvStatus.text = "체커보드를 카메라에 보여주세요."
        }

        btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("캘리브레이션 초기화")
                .setMessage("저장된 캘리브레이션 데이터를 삭제하고 처음부터 다시 시작합니다.")
                .setPositiveButton("초기화") { _, _ ->
                    frameAnalyzer.mode = FrameAnalyzer.Mode.IDLE
                    calibrationManager.resetAll()
                    tvCalibInfo.visibility = View.GONE
                    updateUI()
                    tvStatus.text = "초기화 완료. 캘리브레이션을 다시 시작하세요."
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI state machine
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateUI() {
        val calibrated   = calibrationManager.isCalibrated
        val calibrating  = frameAnalyzer.mode == FrameAnalyzer.Mode.CALIBRATING
        val measuring    = frameAnalyzer.mode == FrameAnalyzer.Mode.MEASURING

        btnStartCalibration.visibility = if (!calibrating)             View.VISIBLE else View.GONE
        btnCapture.visibility          = if (calibrating)              View.VISIBLE else View.GONE
        btnFinishCalib.visibility      = View.GONE   // Auto-triggered; shown only if needed
        btnMeasure.visibility          = if (calibrated && !measuring) View.VISIBLE else View.GONE
        btnReset.visibility            = if (calibrated)               View.VISIBLE else View.GONE

        if (calibrated && !calibrating && !measuring) {
            tvStatus.text = "캘리브레이션 완료. '측정 시작' 버튼을 누르세요."
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews() {
        previewView         = findViewById(R.id.previewView)
        overlayView         = findViewById(R.id.overlayView)
        tvStatus            = findViewById(R.id.tvStatus)
        tvCalibInfo         = findViewById(R.id.tvCalibInfo)
        btnStartCalibration = findViewById(R.id.btnStartCalibration)
        btnCapture          = findViewById(R.id.btnCapture)
        btnFinishCalib      = findViewById(R.id.btnFinishCalib)
        btnMeasure          = findViewById(R.id.btnMeasure)
        btnReset            = findViewById(R.id.btnReset)
    }

    override fun onDestroy() {
        super.onDestroy()
        // MEDIUM-3 fix: shutdownNow() cancels queued tasks immediately;
        // awaitTermination gives actively-running task max 2s to finish gracefully.
        cameraExecutor.shutdownNow()
        try {
            cameraExecutor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        calibrationManager.resetCaptures()   // Release in-memory Mats
    }
}
