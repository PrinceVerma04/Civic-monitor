package com.civicservices.monitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.RectF
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.util.Consumer
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var detector: Detector? = null
    private lateinit var reporter: IncidentReporter
    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var recordingIndicator: TextView
    private lateinit var recordButton: ImageButton
    private lateinit var captureButton: ImageButton
    private lateinit var captureFlash: View
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recordingStartMs = 0L
    private var recPulse: android.animation.ObjectAnimator? = null

    // Manual "live recording" now records the SCREEN (camera + detection boxes) via
    // MediaProjection, because CameraX's VideoCapture only captures the raw sensor stream and
    // the overlay boxes never reach that file.
    @Volatile private var isScreenRecording = false
    private lateinit var rootLayout: View
    private lateinit var controlBar: View
    private lateinit var backButton: ImageButton
    private lateinit var aboutButton: ImageButton

    // Latest upright frame the detector just processed, reused for manual photo capture so a
    // snapshot matches exactly what's on screen (same rotation-corrected frame, no separate
    // ImageCapture use case needed alongside Preview+ImageAnalysis+VideoCapture).
    @Volatile private var latestFrame: Bitmap? = null
    // Detections for the latest frame, so a saved photo can be annotated with the same boxes
    // the user saw on screen (the manual/auto photo used to save a blank frame).
    @Volatile private var latestDetections: List<Detection> = emptyList()

    // OverlayEffect draws the detection boxes INTO the recorded (VideoCapture) stream, so the
    // exported video contains the boxes without screen recording. It needs the detections plus
    // a matrix mapping our upright detection coordinates into sensor space (the effect then maps
    // sensor -> the video buffer via Frame.getSensorToBufferTransform()).
    private var overlayEffect: OverlayEffect? = null
    @Volatile private var overlayDetections: List<Detection> = emptyList()
    @Volatile private var uprightToSensor: Matrix = Matrix()

    private var lastFrameTime = 0L
    private val cameraId = "phone-1"

    private lateinit var locationProvider: LocationProvider

    // Reused for the burned-in location/time stamp on photos and video frames.
    private val stampDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** "📍 12.971600, 77.594600  •  2026-09-29 14:03:21" — or "no GPS" if there's no fix yet. */
    private fun stampText(): String {
        val coords = LocationProvider.format(locationProvider.current())
        val time = stampDateFormat.format(Date())
        return if (coords != null) "📍 $coords  •  $time" else "📍 no GPS  •  $time"
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) startCamera()
        else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Camera permission denied"
        }
        // Location is optional — start tracking if the user granted it.
        if (locationProvider.hasPermission()) locationProvider.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.applySavedMode(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        controlBar = findViewById(R.id.controlBar)
        overlayView = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)
        recordingIndicator = findViewById(R.id.recordingIndicator)
        recordButton = findViewById(R.id.recordButton)
        captureButton = findViewById(R.id.captureButton)
        captureFlash = findViewById(R.id.captureFlash)
        backButton = findViewById(R.id.backButton)
        aboutButton = findViewById(R.id.aboutButton)
        reporter = IncidentReporter(this)

        aboutButton.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        backButton.setOnClickListener { finish() }
        captureButton.setOnClickListener { Anim.tap(it); capturePhoto() }
        recordButton.setOnClickListener { Anim.tap(it); toggleRecording() }

        ContextCompat.registerReceiver(
            this, recStoppedReceiver, IntentFilter(ScreenRecordService.ACTION_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        maybeRequestNotificationPermission()

        detector = try {
            Detector(this)
        } catch (e: Exception) {
            statusText.text = "No model.tflite in assets/ — camera preview only, no detection"
            null
        }

        locationProvider = LocationProvider(this)

        // Request whatever isn't granted yet: camera (required) + location (optional, for geo-tagging).
        val toRequest = mutableListOf<String>()
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) toRequest += Manifest.permission.CAMERA
        if (!locationProvider.hasPermission()) {
            toRequest += Manifest.permission.ACCESS_FINE_LOCATION
            toRequest += Manifest.permission.ACCESS_COARSE_LOCATION
        }

        if (cameraGranted) startCamera()
        if (locationProvider.hasPermission()) locationProvider.start()
        if (toRequest.isNotEmpty()) requestPermissions.launch(toRequest.toTypedArray())
    }

    /**
     * One binding used the whole time the screen is open: Preview + ImageAnalysis (detector)
     * + VideoCapture, all live simultaneously. Recording used to unbind ImageAnalysis entirely
     * (dropping live detection while recording) — this binds all three up front instead, so
     * starting/stopping a recording is just starting/stopping the Recorder, no camera rebind.
     */
    private fun startCamera() {
        val previewView = findViewById<PreviewView>(R.id.previewView)
        // COMPATIBLE (TextureView) so MediaProjection screen recording captures the preview;
        // the default SurfaceView can record as a black rectangle.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Cap analysis frames to ~640x480 so the per-frame rotate + letterbox work stays cheap.
            // The detector down-samples to the model's input size anyway, so bigger frames only cost
            // latency without helping accuracy — part of the on-device speed-up.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy -> analyzeFrame(imageProxy) }

            val recorder = Recorder.Builder().build()
            val capture = VideoCapture.withOutput(recorder)
            videoCapture = capture

            // Draw detection boxes into the VIDEO_CAPTURE stream only (the on-screen preview
            // keeps using OverlayView with its glow styling, so no double-drawing).
            val effect = OverlayEffect(
                CameraEffect.VIDEO_CAPTURE,
                0,
                Handler(Looper.getMainLooper()),
                Consumer { /* effect error — ignore, video still records without boxes */ }
            )
            effect.setOnDrawListener { frame ->
                val canvas = frame.overlayCanvas
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                val dets = overlayDetections
                if (dets.isNotEmpty()) {
                    val m = Matrix(uprightToSensor)
                    m.postConcat(frame.sensorToBufferTransform)
                    DetectionOverlay.drawTransformed(
                        canvas, dets, m, minOf(frame.size.width, frame.size.height)
                    )
                }
                // Burn the live location + time into every recorded frame (same buffer-space
                // convention as the box labels), so the exported video carries the coordinates.
                DetectionOverlay.drawStamp(canvas, stampText(), frame.size.width, frame.size.height)
                true
            }
            overlayEffect = effect

            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(analysis)
                .addUseCase(capture)
                .addEffect(effect)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        if (savePhoto("CIVIC") != null) {
            flashScreen()
            Toast.makeText(this, "Photo saved", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No frame yet, try again", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Writes the latest processed frame to today's vault WITH the detection boxes drawn on it,
     * so the saved photo shows what was detected (a blank frame is useless as evidence).
     * Returns the file, or null if no frame yet.
     */
    private fun savePhoto(prefix: String): File? {
        val frame = latestFrame ?: return null
        val annotated = DetectionOverlay.render(frame, latestDetections)
        // Burn the location + time onto the pixels. render() returns the shared `frame` itself when
        // there are no boxes, so copy first in that case — we must never draw on the reused frame.
        val stamped = if (annotated === frame) frame.copy(Bitmap.Config.ARGB_8888, true) else annotated
        DetectionOverlay.drawStamp(android.graphics.Canvas(stamped), stampText(), stamped.width, stamped.height)

        val file = File(MediaVault.todayDir(this), "${prefix}_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { out -> stamped.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        if (stamped !== frame) stamped.recycle()
        // Also geo-tag the EXIF so the coordinates are machine-readable, not just visible.
        GeoTag.stamp(file, locationProvider.current())
        return file
    }

    /** White flash overlay for capture feedback. */
    private fun flashScreen() {
        captureFlash.visibility = View.VISIBLE
        captureFlash.alpha = 0.9f
        captureFlash.animate()
            .alpha(0f)
            .setDuration(320)
            .withEndAction { captureFlash.visibility = View.GONE }
            .start()
    }

    private val timerTick = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - recordingStartMs) / 1000
            recordingIndicator.text = String.format(Locale.US, "● REC %02d:%02d", elapsed / 60, elapsed % 60)
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun toggleRecording() {
        val existing = activeRecording
        if (existing != null) {
            existing.stop()
            activeRecording = null
            return
        }
        startRecording()
    }

    // ---- Screen recording (kept as a fallback path; record button uses CameraX + OverlayEffect
    //      now, but this remains wired to the projection permission flow in case it's needed) ----
    private val projectionLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            beginScreenRecording(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen recording permission needed", Toast.LENGTH_SHORT).show()
        }
    }

    // Fires when the service stops recording (user tap, or the projection ended) so the HUD
    // always comes back even if recording ended outside the record button.
    private val recStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isScreenRecording) endScreenRecordingUi()
        }
    }

    private fun toggleScreenRecording() {
        if (isScreenRecording) {
            stopScreenRecording()
        } else {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun beginScreenRecording(resultCode: Int, data: Intent) {
        isScreenRecording = true
        setChromeVisible(false) // hide UI so only camera + boxes are in the video
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
        // Tap anywhere to stop, since the record button is hidden during recording.
        rootLayout.setOnClickListener { stopScreenRecording() }
        Toast.makeText(this, "Recording — tap screen to stop", Toast.LENGTH_LONG).show()
    }

    private fun stopScreenRecording() {
        startService(Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP))
        endScreenRecordingUi()
    }

    private fun endScreenRecordingUi() {
        isScreenRecording = false
        rootLayout.setOnClickListener(null)
        rootLayout.isClickable = false
        setChromeVisible(true)
    }

    /** Show/hide all HUD chrome so it doesn't appear in a screen recording. */
    private fun setChromeVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        statusText.visibility = v
        controlBar.visibility = v
        backButton.visibility = v
        aboutButton.visibility = v
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* FG service runs regardless */ }

    /** Starts a manual recording that runs until the user taps the record button again. */
    private fun startRecording() {
        val capture = videoCapture ?: return
        if (activeRecording != null) return

        val file = File(MediaVault.todayDir(this), "CIVIC_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(file).build()

        activeRecording = capture.output
            .prepareRecording(this, outputOptions)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recordingStartMs = System.currentTimeMillis()
                        recordingIndicator.visibility = View.VISIBLE
                        recPulse = Anim.pulse(recordingIndicator)
                        mainHandler.post(timerTick)
                    }
                    is VideoRecordEvent.Finalize -> {
                        mainHandler.removeCallbacks(timerTick)
                        recPulse?.cancel()
                        recPulse = null
                        recordingIndicator.alpha = 1f
                        recordingIndicator.visibility = View.GONE
                        val message = if (event.hasError()) "Recording failed" else "Video saved"
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val activeDetector = detector
        if (activeDetector == null) {
            imageProxy.close()
            return
        }

        // Capture geometry before rotating, to map detections (in upright coords) back to sensor
        // space for the video OverlayEffect.
        val rotation = imageProxy.imageInfo.rotationDegrees
        val aw = imageProxy.width.toFloat()
        val ah = imageProxy.height.toFloat()
        val sensorToBufferAnalysis = Matrix(imageProxy.imageInfo.sensorToBufferTransformMatrix)

        val bitmap = imageProxy.toUprightBitmap()
        latestFrame = bitmap
        latestDetections = emptyList()
        // The 16-class model (2026-09-07) predicts streetlight_working vs
        // streetlight_not_working directly, so the NightLightChecker brightness
        // heuristic it used to depend on is gone from this path. That workaround only
        // existed because no dataset labelled lit/unlit streetlights; ours now does,
        // and it works in daylight too — the old code discarded every streetlight
        // detection before 19:00 because the heuristic could not judge them.
        val finalDetections = activeDetector.detect(bitmap)
        latestDetections = finalDetections

        // Build upright -> sensor matrix. toUprightBitmap rotates the analysis buffer by
        // `rotation`; reconstruct that exact rotate+translate, invert it (upright -> buffer),
        // then compose with buffer -> sensor (inverse of the analysis sensor transform).
        val rmat = Matrix().apply {
            postRotate(rotation.toFloat())
            val b = RectF(0f, 0f, aw, ah)
            mapRect(b)
            postTranslate(-b.left, -b.top)
        }
        val bufferFromUpright = Matrix().also { rmat.invert(it) }
        val sensorFromBuffer = Matrix().also { sensorToBufferAnalysis.invert(it) }
        val u2s = Matrix(bufferFromUpright).apply { postConcat(sensorFromBuffer) }
        uprightToSensor = u2s
        overlayDetections = finalDetections

        // Current best GPS fix, attached to every report/photo this frame produces.
        val location = locationProvider.current()

        // Healthy states (manhole_closed, streetlight_working) are still drawn, so you
        // can see the model looked, but they are not civic incidents.
        finalDetections
            .filter { it.label in Detector.ISSUE_CLASSES }
            .forEach { reporter.maybeReport(bitmap, it, cameraId, location) }

        val now = System.currentTimeMillis()
        val fps = if (lastFrameTime > 0) 1000f / (now - lastFrameTime) else 0f
        lastFrameTime = now

        val accel = detector?.accelerator ?: "CPU"
        val coords = LocationProvider.format(location)
        runOnUiThread {
            overlayView.update(finalDetections, bitmap.width, bitmap.height)
            val base = "%.1f FPS · %s · %d det".format(fps, accel, finalDetections.size)
            statusText.text = if (coords != null) "$base\n📍 $coords" else "$base\n📍 locating…"
        }

        imageProxy.close()
    }

    /**
     * ImageAnalysis delivers frames in the camera sensor's native orientation (landscape on
     * most phones), NOT the upright orientation CameraX's Preview auto-corrects for display.
     * Without rotating here, a phone held in portrait feeds the model sideways frames it was
     * never trained on — which was silently biasing every detection toward one class.
     */
    private fun ImageProxy.toUprightBitmap(): Bitmap {
        val buffer = planes[0].buffer
        val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)

        val rotation = imageInfo.rotationDegrees
        if (rotation == 0) return raw

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        // raw is a throwaway intermediate; without recycling, portrait mode leaks one
        // full-frame bitmap per frame on top of the rotated copy we keep.
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    override fun onDestroy() {
        super.onDestroy()
        locationProvider.stop()
        activeRecording?.stop()
        if (isScreenRecording) {
            startService(Intent(this, ScreenRecordService::class.java).setAction(ScreenRecordService.ACTION_STOP))
        }
        runCatching { unregisterReceiver(recStoppedReceiver) }
        overlayEffect?.close()
        mainHandler.removeCallbacks(timerTick)
        detector?.close()
        cameraExecutor.shutdown()
    }
}
