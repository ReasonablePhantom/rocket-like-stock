package com.brucelyu.rocketheaddash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Size
import android.view.View
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Overlay view rendered above PreviewView inside the PiP card,
 * drawing mirrored face bounds, eye/nose landmarks, and a continuous steer indicator bar.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile
    private var mirroredBox: RectF? = null
    @Volatile
    private var mirroredNose: PointF? = null
    @Volatile
    private var mirroredLeftEye: PointF? = null
    @Volatile
    private var mirroredRightEye: PointF? = null
    @Volatile
    private var currentSteer: Float = 0f
    @Volatile
    private var mouthActive: Boolean = false
    @Volatile
    private var calibrated: Boolean = false

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.argb(190, 34, 211, 238)
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(34, 211, 238)
    }

    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(140, 15, 23, 42)
    }

    private val centerTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(170, 226, 232, 240)
    }

    fun updateOverlay(
        box: RectF?,
        nose: PointF?,
        leftEye: PointF?,
        rightEye: PointF?,
        steer: Float,
        mouthOpen: Boolean,
        isCalibrated: Boolean
    ) {
        mirroredBox = box
        mirroredNose = nose
        mirroredLeftEye = leftEye
        mirroredRightEye = rightEye
        currentSteer = steer.coerceIn(-1f, 1f)
        mouthActive = mouthOpen
        calibrated = isCalibrated
        postInvalidate()
    }

    fun clear() {
        mirroredBox = null
        mirroredNose = null
        mirroredLeftEye = null
        mirroredRightEye = null
        currentSteer = 0f
        mouthActive = false
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Top continuous steer track inside PiP
        val pad = 8f
        val barTop = 6f
        val barH = 8f
        canvas.drawRoundRect(pad, barTop, w - pad, barTop + barH, 4f, 4f, barBgPaint)

        val midX = w * 0.5f
        canvas.drawRect(midX - 1f, barTop - 1f, midX + 1f, barTop + barH + 1f, centerTickPaint)

        val usableHalf = (w - pad * 2f) * 0.5f - 4f
        val knobX = midX + currentSteer * usableHalf
        dotPaint.color = when {
            mouthActive -> Color.rgb(250, 204, 21)
            !calibrated -> Color.rgb(251, 146, 60)
            else -> Color.rgb(34, 211, 238)
        }
        canvas.drawCircle(knobX, barTop + barH * 0.5f, 5.5f, dotPaint)

        val highlightColor = when {
            mouthActive -> Color.rgb(250, 204, 21)
            !calibrated -> Color.rgb(251, 146, 60)
            else -> Color.rgb(34, 211, 238)
        }

        mirroredBox?.let { b ->
            boxPaint.color = Color.argb(
                185,
                Color.red(highlightColor),
                Color.green(highlightColor),
                Color.blue(highlightColor)
            )
            canvas.drawRoundRect(b.left * w, b.top * h, b.right * w, b.bottom * h, 14f, 14f, boxPaint)
        }

        dotPaint.color = highlightColor
        mirroredLeftEye?.let { canvas.drawCircle(it.x * w, it.y * h, 4.5f, dotPaint) }
        mirroredRightEye?.let { canvas.drawCircle(it.x * w, it.y * h, 4.5f, dotPaint) }
        mirroredNose?.let { canvas.drawCircle(it.x * w, it.y * h, 6.5f, dotPaint) }
    }
}

/**
 * CameraX 1.4.2 + ML Kit Face Detection 16.1.7 continuous head controller.
 * Reuses First Play App's verified 16KB-aligned camera/view pipeline, replacing discrete
 * 3-lane hysteresis with countdown baseline anchoring + One-Euro adaptive filtering.
 */
class FaceControlManager(
    private val context: Context,
    private val previewView: PreviewView,
    private val overlayView: FaceOverlayView,
    private val onSteerChanged: (steer: Float) -> Unit,
    private val onMouthShieldTrigger: () -> Unit,
    private val onCalibrationProgress: (collected: Int, target: Int, done: Boolean) -> Unit,
    private val onStatusText: (text: String) -> Unit,
    private val onError: (msg: String) -> Unit
) {

    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mapper = HeadSteerMapper(targetCalibrationSamples = 9)

    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.18f)
            .build()
        FaceDetection.getClient(options)
    }

    @Volatile
    private var isStarted = false
    @Volatile
    var latestSteer: Float = 0f
        private set

    fun start(lifecycleOwner: LifecycleOwner) {
        if (isStarted) return
        isStarted = true
        beginCalibration()

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (!isStarted) return@addListener
            previewView.post {
                if (!isStarted) return@post
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()

                    val preview = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(cameraExecutor, ::analyzeImage) }

                    provider.unbindAll()
                    val viewPort = previewView.viewPort
                    if (viewPort != null) {
                        val useCaseGroup = UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(analysis)
                            .setViewPort(viewPort)
                            .build()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            useCaseGroup
                        )
                    } else {
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            analysis
                        )
                    }
                    onStatusText("👀 找人脸")
                } catch (e: Exception) {
                    isStarted = false
                    onError(e.localizedMessage ?: "前置摄像头启动失败")
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun beginCalibration() {
        mapper.beginCalibration()
        latestSteer = 0f
        onSteerChanged(0f)
        onCalibrationProgress(0, 9, false)
        onStatusText("🎯 采样基准")
    }

    fun finalizeCalibrationIfReady() {
        mapper.finalizeCalibrationIfSamplesExist()
        if (mapper.isCalibrated) {
            onCalibrationProgress(mapper.calibrationCount, 9, true)
        }
    }

    fun stop() {
        isStarted = false
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        overlayView.clear()
    }

    fun recalibrate() {
        beginCalibration()
    }

    fun release() {
        stop()
        try {
            faceDetector.close()
        } catch (_: Exception) {}
        cameraExecutor.shutdown()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeImage(imageProxy: androidx.camera.core.ImageProxy) {
        if (!isStarted) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rot = imageProxy.imageInfo.rotationDegrees
        val rawW = imageProxy.width.toFloat()
        val rawH = imageProxy.height.toFloat()
        val uprightCrop = rotateCropRect(imageProxy.cropRect, rawW, rawH, rot)
        val inputImage = InputImage.fromMediaImage(mediaImage, rot)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (!isStarted) return@addOnSuccessListener
                if (faces.isEmpty()) {
                    overlayView.clear()
                    onStatusText("👀 找人脸")
                    return@addOnSuccessListener
                }
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    ?: return@addOnSuccessListener
                handleDetectedFace(face, uprightCrop)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun rotateCropRect(crop: Rect, rawW: Float, rawH: Float, rot: Int): RectF {
        return when (rot) {
            90 -> RectF(rawH - crop.bottom, crop.left.toFloat(), rawH - crop.top, crop.right.toFloat())
            180 -> RectF(rawW - crop.right, rawH - crop.bottom, rawW - crop.left, rawH - crop.top)
            270 -> RectF(crop.top.toFloat(), rawW - crop.right, crop.bottom.toFloat(), rawW - crop.left)
            else -> RectF(crop.left.toFloat(), crop.top.toFloat(), crop.right.toFloat(), crop.bottom.toFloat())
        }
    }

    private fun handleDetectedFace(face: Face, visibleRect: RectF) {
        val visibleW = visibleRect.width().coerceAtLeast(1f)
        val visibleH = visibleRect.height().coerceAtLeast(1f)
        // Negating EulerY aligns positive angles with physical head turn to the user's right.
        val yawDeg = -face.headEulerAngleY
        val centerNormX = (face.boundingBox.centerX().toFloat() - visibleRect.left) / visibleW
        val mirroredOffsetX = 0.5f - centerNormX
        val mouthRatio = computeMouthOpenRatio(face)
        val timestampSec = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0

        val out = mapper.processPose(yawDeg, mirroredOffsetX, mouthRatio, timestampSec)
        latestSteer = out.steer

        if (!out.isCalibrated) {
            onCalibrationProgress(out.calibrationCount, out.calibrationTarget, false)
            onStatusText("🎯 ${out.calibrationCount}/${out.calibrationTarget}")
        } else {
            onCalibrationProgress(out.calibrationCount, out.calibrationTarget, true)
            onSteerChanged(out.steer)
            if (out.triggerShield) {
                onMouthShieldTrigger()
            }
            val statusText = when {
                out.mouthActive -> "😮 护盾冲刺!"
                out.steer < -0.18f -> "◀ 左舵"
                out.steer > 0.18f -> "▶ 右舵"
                else -> "🎯 已回正"
            }
            onStatusText(statusText)
        }

        val b = face.boundingBox
        val mirroredBox = RectF(
            1f - ((b.right.toFloat() - visibleRect.left) / visibleW),
            (b.top.toFloat() - visibleRect.top) / visibleH,
            1f - ((b.left.toFloat() - visibleRect.left) / visibleW),
            (b.bottom.toFloat() - visibleRect.top) / visibleH
        )
        val mapLandmark = { type: Int ->
            face.getLandmark(type)?.position?.let { pt ->
                PointF(
                    1f - ((pt.x - visibleRect.left) / visibleW),
                    (pt.y - visibleRect.top) / visibleH
                )
            }
        }
        overlayView.updateOverlay(
            box = mirroredBox,
            nose = mapLandmark(FaceLandmark.NOSE_BASE),
            leftEye = mapLandmark(FaceLandmark.LEFT_EYE),
            rightEye = mapLandmark(FaceLandmark.RIGHT_EYE),
            steer = out.steer,
            mouthOpen = out.mouthActive,
            isCalibrated = out.isCalibrated
        )
    }

    private fun computeMouthOpenRatio(face: Face): Float? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: return null
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return null
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return null

        val eyeDist = hypot(
            (rightEye.x - leftEye.x).toDouble(),
            (rightEye.y - leftEye.y).toDouble()
        ).toFloat().coerceAtLeast(16f)
        val mouthCornersY = (mouthLeft.y + mouthRight.y) * 0.5f
        return (mouthBottom.y - mouthCornersY) / eyeDist
    }
}
