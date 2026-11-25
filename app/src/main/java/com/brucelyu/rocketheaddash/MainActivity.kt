package com.brucelyu.rocketheaddash

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * MainActivity hosting RocketGameView (SurfaceView) + optional CameraX COMPATIBLE PiP overlay.
 * Preserves First Play App's verified Android 16 immersive mode, permission flow, and Z-order fix.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var gameView: RocketGameView
    private lateinit var modeToggleBtn: TextView
    private lateinit var pipCard: LinearLayout
    private lateinit var previewView: PreviewView
    private lateinit var faceOverlayView: FaceOverlayView
    private lateinit var faceStatusLabel: TextView
    private var faceControlManager: FaceControlManager? = null
    private var isFaceModeActive = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableFaceMode()
        } else {
            Toast.makeText(this, "未授予相机权限，继续使用触屏拖动模式", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        gameView = RocketGameView(this)
        root.addView(
            gameView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Top-center Face / Touch dual-mode toggle
        modeToggleBtn = TextView(this).apply {
            text = "📷 开启头控"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(226, 232, 240))
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = createPillDrawable(
                fillColor = Color.argb(205, 15, 23, 42),
                strokeColor = Color.argb(140, 148, 163, 184),
                cornerDp = 20
            )
            setOnClickListener { toggleFaceMode() }
        }
        val toggleParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(24)
        }
        root.addView(modeToggleBtn, toggleParams)

        // Top-right toggleable Camera PiP (COMPATIBLE mode avoids SurfaceView Z-fighting)
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        faceOverlayView = FaceOverlayView(this)

        val cameraBox = FrameLayout(this).apply {
            val radiusPx = dp(10).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            clipToOutline = true
            addView(
                previewView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                faceOverlayView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setOnClickListener {
                faceControlManager?.recalibrate()
                Toast.makeText(this@MainActivity, "🎯 零点基准已一键回中", Toast.LENGTH_SHORT).show()
            }
        }

        faceStatusLabel = TextView(this).apply {
            text = "👀 找人脸"
            textSize = 9.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(226, 232, 240))
        }
        val calibrateBtn = TextView(this).apply {
            text = "🎯回中"
            textSize = 9.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(103, 232, 249))
            setPadding(dp(5), dp(2), dp(5), dp(2))
            background = createPillDrawable(
                fillColor = Color.argb(60, 34, 211, 238),
                strokeColor = Color.argb(140, 34, 211, 238),
                cornerDp = 6
            )
            setOnClickListener {
                faceControlManager?.recalibrate()
                Toast.makeText(this@MainActivity, "🎯 零点基准已一键回中", Toast.LENGTH_SHORT).show()
            }
        }
        val pipFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            addView(
                faceStatusLabel,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(calibrateBtn)
        }

        pipCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = createPillDrawable(
                fillColor = Color.argb(225, 6, 10, 20),
                strokeColor = Color.argb(180, 34, 211, 238),
                cornerDp = 12
            )
            addView(cameraBox, LinearLayout.LayoutParams(dp(90), dp(112)))
            addView(pipFooter, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val pipParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(64)
            marginEnd = dp(12)
        }
        root.addView(pipCard, pipParams)

        setContentView(root)
        hideSystemUI()

        faceControlManager = FaceControlManager(
            context = this,
            previewView = previewView,
            overlayView = faceOverlayView,
            onSteerChanged = { steer ->
                if (isFaceModeActive) {
                    gameView.setHeadSteer(steer)
                }
            },
            onMouthShieldTrigger = {
                if (isFaceModeActive) {
                    gameView.triggerMouthShield()
                }
            },
            onCalibrationProgress = { collected, target, done ->
                gameView.updateCalibrationProgress(collected, target, done)
            },
            onStatusText = { status ->
                mainHandler.post { faceStatusLabel.text = status }
            },
            onError = { errMsg ->
                mainHandler.post {
                    disableFaceMode()
                    Toast.makeText(this, "人脸识别启动失败: $errMsg", Toast.LENGTH_SHORT).show()
                }
            }
        )

        gameView.onStartCalibrationRequested = {
            if (isFaceModeActive) {
                faceControlManager?.beginCalibration()
            }
        }
        gameView.onFinalizeCalibrationRequested = {
            if (isFaceModeActive) {
                faceControlManager?.finalizeCalibrationIfReady()
            }
        }
        gameView.onGameStateChanged = { newState ->
            if (isFaceModeActive) {
                pipCard.visibility = if (newState == RocketGameView.GameState.GAMEOVER) View.GONE else View.VISIBLE
            }
        }
    }

    private fun createPillDrawable(fillColor: Int, strokeColor: Int, cornerDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(fillColor)
            setStroke(dp(1) + 1, strokeColor)
        }
    }

    private fun toggleFaceMode() {
        if (isFaceModeActive) {
            disableFaceMode()
        } else {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                enableFaceMode()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun enableFaceMode() {
        isFaceModeActive = true
        gameView.isFaceControlEnabled = true
        pipCard.visibility = View.VISIBLE
        modeToggleBtn.text = "📷 头控中 · 切触屏"
        modeToggleBtn.setTextColor(Color.rgb(103, 232, 249))
        modeToggleBtn.background = createPillDrawable(
            fillColor = Color.argb(220, 8, 51, 68),
            strokeColor = Color.rgb(34, 211, 238),
            cornerDp = 20
        )
        faceControlManager?.start(this)
    }

    private fun disableFaceMode() {
        isFaceModeActive = false
        gameView.isFaceControlEnabled = false
        pipCard.visibility = View.GONE
        modeToggleBtn.text = "📷 开启头控"
        modeToggleBtn.setTextColor(Color.rgb(226, 232, 240))
        modeToggleBtn.background = createPillDrawable(
            fillColor = Color.argb(205, 15, 23, 42),
            strokeColor = Color.argb(140, 148, 163, 184),
            cornerDp = 20
        )
        faceControlManager?.stop()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        gameView.resume()
        if (isFaceModeActive) {
            faceControlManager?.start(this)
        }
    }

    override fun onPause() {
        super.onPause()
        gameView.pause()
        if (isFaceModeActive) {
            faceControlManager?.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        faceControlManager?.release()
        gameView.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        try {
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {
            try {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
            } catch (_: Exception) {}
        }
    }
}
