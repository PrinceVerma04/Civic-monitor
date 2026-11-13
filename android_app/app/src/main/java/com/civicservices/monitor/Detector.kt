package com.civicservices.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Detection(val classId: Int, val label: String, val confidence: Float, val box: RectF)

/**
 * Wraps a YOLO11-family TFLite export (output shape [1, 4+numClasses, numAnchors], no
 * objectness channel) with manual decode + NMS, since a raw Interpreter export has no
 * bundled metadata for the TFLite Task library to use automatically.
 */
class Detector(context: Context, modelPath: String = "model.tflite") {

    // Must match model_update_v2/data_v2.yaml order exactly (ids 0-9, contiguous).
    // v2 model (2026-10-03): 10 classes — the 7 animal species of v1 are merged into a
    // single `animal` class that the model now predicts directly (no on-device merge
    // needed anymore), and synthetic night/rain/fog augmentation was added for weather
    // robustness.
    val labels = listOf(
        "garbage_pile",              // 0
        "pothole",                   // 1
        "encroachment",              // 2
        "billboard_hoarding",        // 3
        "manhole_closed",            // 4
        "manhole_open",              // 5
        "manhole_broken",            // 6
        "streetlight_working",       // 7
        "streetlight_not_working",   // 8
        "animal",                    // 9
    )

    companion object {
        private const val TAG = "Detector"

        /**
         * The v2 model predicts a single `animal` class (id 9) directly, so the old on-device
         * species-merge is gone. We keep the label constant because ISSUE_CLASSES references it.
         */
        const val ANIMAL_LABEL = "animal"

        /**
         * Classes that represent an actual civic issue worth filing a report for.
         * `manhole_closed` and `streetlight_working` are the healthy states — the model
         * detects them so the overlay can show it looked and found nothing wrong, but
         * filing an incident for a perfectly good streetlight would flood the report log.
         */
        val ISSUE_CLASSES = setOf(
            "garbage_pile", "pothole", "encroachment", "billboard_hoarding",
            "manhole_open", "manhole_broken", "streetlight_not_working",
            ANIMAL_LABEL,
        )

        /** Healthy states — detected and drawn, never reported. */
        val OK_CLASSES = setOf("manhole_closed", "streetlight_working")
    }

    /** Human-readable delegate actually running inference, for the on-screen status line. */
    var accelerator: String = "CPU"
        private set

    private var nnApiDelegate: NnApiDelegate? = null

    private val interpreter: Interpreter
    private val inputSize: Int
    private val nchw: Boolean // Ultralytics' TFLite export is [1,3,H,W] (channels-first), not the [1,H,W,3] TFLite default
    private val numClasses: Int
    private val numAnchors: Int

    // Reused across every frame. The old code allocated all of these PER FRAME — most
    // damagingly a ~4.7 MB direct ByteBuffer for the input, whose off-heap memory is only
    // reclaimed on GC finalization. At video frame rates that starved native memory and the
    // OS killed the app after a few minutes ("app stopped" / detection freezes). Allocating
    // once and refilling in place removes essentially all per-frame allocation.
    private val inputBuffer: ByteBuffer
    private val pixels: IntArray
    private val output: Array<Array<FloatArray>>
    private val letterboxed: Bitmap
    private val letterboxCanvas: Canvas
    private val drawPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    init {
        val model = FileUtil.loadMappedFile(context, modelPath)

        // Try to offload inference to the phone's neural accelerator (NPU/DSP/GPU) via NNAPI —
        // for an int8-quantized model like ours this is by far the biggest latency win, often
        // several times faster than CPU. If NNAPI init fails or isn't available on this device
        // we fall back to the optimized CPU path (XNNPACK + multi-threading), so the app still
        // runs everywhere. This is the fix for the on-device latency you saw.
        interpreter = buildInterpreter(model)

        val inputShape = interpreter.getInputTensor(0).shape()
        nchw = inputShape[1] == 3
        inputSize = if (nchw) inputShape[2] else inputShape[1]

        val outputShape = interpreter.getOutputTensor(0).shape() // [1, 4+numClasses, numAnchors]
        numClasses = outputShape[1] - 4
        numAnchors = outputShape[2]

        inputBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).apply { order(ByteOrder.nativeOrder()) }
        pixels = IntArray(inputSize * inputSize)
        output = Array(1) { Array(4 + numClasses) { FloatArray(numAnchors) } }
        letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        letterboxCanvas = Canvas(letterboxed)
    }

    /**
     * Build the interpreter, preferring NNAPI hardware acceleration and falling back to a fast
     * CPU configuration. Verifies the delegate by allocating tensors before committing to it, so
     * a device whose NNAPI driver rejects the model degrades gracefully instead of crashing.
     */
    private fun buildInterpreter(model: ByteBuffer): Interpreter {
        try {
            val delegate = NnApiDelegate()
            val opts = Interpreter.Options().apply {
                addDelegate(delegate)
                setNumThreads(4) // used for any ops NNAPI can't take
            }
            val interp = Interpreter(model, opts)
            interp.allocateTensors() // forces NNAPI compilation now; throws here if unsupported
            nnApiDelegate = delegate
            accelerator = "NNAPI"
            Log.i(TAG, "Inference accelerator: NNAPI")
            return interp
        } catch (e: Throwable) {
            Log.w(TAG, "NNAPI unavailable (${e.message}); falling back to CPU", e)
            runCatching { nnApiDelegate?.close() }
            nnApiDelegate = null
        }
        // CPU fallback: XNNPACK on, multi-threaded.
        accelerator = "CPU"
        Log.i(TAG, "Inference accelerator: CPU (XNNPACK)")
        return Interpreter(model, Interpreter.Options().apply {
            setUseXNNPACK(true)
            setNumThreads(4)
        })
    }

    // confThreshold raised 0.25 -> 0.35 to cut the false positives seen in the field. 0.25 was
    // Ultralytics' default and kept faint far-away detections, but on-device that also surfaced
    // spurious boxes; 0.35 is a good middle ground. Lower it toward 0.25 if real objects start
    // getting missed, raise toward 0.45 if false positives return.
    fun detect(bitmap: Bitmap, confThreshold: Float = 0.35f, iouThreshold: Float = 0.45f): List<Detection> {
        // LETTERBOX, never stretch. This used to be
        //     Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        // which squashes a 4:3 camera frame into a square and distorts every object -
        // a round manhole cover arrives as an ellipse. Ultralytics letterboxes (pads
        // to square, preserving aspect) for both training and validation, so stretching
        // fed the model a geometry it was never trained on. Measured off-device:
        // matching the training geometry lifted the hit rate on unseen photos from
        // 34% to 43% with no retraining.
        val scale = min(inputSize.toFloat() / bitmap.width, inputSize.toFloat() / bitmap.height)
        val scaledW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val scaledH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val padX = (inputSize - scaledW) / 2f
        val padY = (inputSize - scaledH) / 2f

        // Scale directly into the reused letterbox bitmap via a destination rect — no
        // per-frame intermediate scaled bitmap.
        letterboxCanvas.drawColor(Color.rgb(114, 114, 114))   // the grey Ultralytics pads with
        letterboxCanvas.drawBitmap(
            bitmap,
            null,
            RectF(padX, padY, padX + scaledW, padY + scaledH),
            drawPaint,
        )
        val input = bitmapToByteBuffer(letterboxed)
        try {
            interpreter.run(input, output)
        } catch (e: IllegalStateException) {
            // Interpreter was closed mid-flight by a concurrent mode switch (e.g. starting a
            // video recording) — drop this one frame instead of crashing the whole app.
            return emptyList()
        }

        // Ultralytics TFLite exports sometimes emit box coords normalized to [0,1] and
        // sometimes in input-pixel space depending on export flags — detect which at runtime.
        var maxCoord = 0f
        for (a in 0 until numAnchors) {
            maxCoord = max(maxCoord, max(output[0][0][a], output[0][1][a]))
        }
        val coordScale = if (maxCoord <= 1.5f) inputSize.toFloat() else 1f

        val candidates = mutableListOf<Detection>()
        for (a in 0 until numAnchors) {
            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val score = output[0][4 + c][a]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }
            if (bestScore < confThreshold) continue

            // Undo the letterbox: strip the padding, then divide by the scale factor.
            // (The old code multiplied by width/inputSize, which is only correct for a
            // stretch — with padding it would place every box in the wrong spot.)
            val cx = (output[0][0][a] * coordScale - padX) / scale
            val cy = (output[0][1][a] * coordScale - padY) / scale
            val w = output[0][2][a] * coordScale / scale
            val h = output[0][3][a] * coordScale / scale

            val box = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)

            // v2 predicts `animal` (and every other class) directly — no on-device merge.
            val outLabel = labels.getOrElse(bestClass) { "class_$bestClass" }
            candidates.add(Detection(bestClass, outLabel, bestScore, box))
        }

        return nonMaxSuppression(candidates, iouThreshold)
    }

    private fun nonMaxSuppression(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { it.classId == best.classId && iou(best.box, it.box) > iouThreshold }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val union = a.width() * a.height() + b.width() * b.height() - interArea
        return if (union <= 0f) 0f else interArea / union
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = inputBuffer
        buffer.rewind()
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        if (nchw) {
            // channel-planar: all R, then all G, then all B
            for (channelShift in intArrayOf(16, 8, 0)) {
                for (pixel in pixels) {
                    buffer.putFloat(((pixel shr channelShift) and 0xFF) / 255f)
                }
            }
        } else {
            // interleaved per pixel: R,G,B, R,G,B, ...
            for (pixel in pixels) {
                buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
                buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
                buffer.putFloat((pixel and 0xFF) / 255f)
            }
        }
        buffer.rewind()
        return buffer
    }

    fun close() {
        interpreter.close()
        runCatching { nnApiDelegate?.close() }
    }
}
