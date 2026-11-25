package com.brucelyu.rocketheaddash

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*

/**
 * Continuous vertical rocket dodging game SurfaceView.
 * Supports 3-2-1 launch countdown calibration, proportional continuous head steering,
 * dynamic rocket roll, fair core hitbox with near-miss graze rewards, altitude sky gradients,
 * and single-finger touch drag fallback.
 */
class RocketGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    enum class GameState { START, CALIBRATING, PLAYING, GAMEOVER }

    private var gameThread: Thread? = null
    @Volatile
    private var isRunning = false

    private val sound = SoundManager()
    private val prefs = context.getSharedPreferences("rocket_head_dash_prefs", Context.MODE_PRIVATE)
    private val waveGenerator = FairWaveGenerator()

    // Callbacks wired by MainActivity
    var onStartCalibrationRequested: (() -> Unit)? = null
    var onFinalizeCalibrationRequested: (() -> Unit)? = null
    var onGameStateChanged: ((GameState) -> Unit)? = null

    @Volatile
    var isFaceControlEnabled: Boolean = false
    @Volatile
    private var calibrationCollected: Int = 0
    @Volatile
    private var calibrationTarget: Int = 9
    @Volatile
    private var calibrationDone: Boolean = false

    private val pendingActions = ConcurrentLinkedQueue<() -> Unit>()

    // Game state & metrics
    private var state = GameState.START
    private var altitude = 0f
    private var score = 0
    private var starsCollected = 0
    private var grazesCount = 0
    private var comboCount = 0
    private var bestScore = prefs.getInt("best_score", 0)
    private var bestAltitude = prefs.getInt("best_altitude", 0)

    // Screen dimensions
    private var screenWidth = 0f
    private var screenHeight = 0f

    // Countdown (3 - 2 - 1)
    private var countdownTimer = 0f
    private var lastCountdownDigit = 4

    // Rocket physics
    private var rocketWidth = 84f
    private var rocketHeight = 140f
    private var rocketX = 0f
    private var rocketY = 0f
    private var rocketRollDeg = 0f
    private var headSteer = 0f
    private var touchSteer = 0f
    private var activeSteer = 0f

    // Speed & progression
    private var worldSpeed = 420f
    private var peakSpeedMult = 1.0f
    private var currentZoneIndex = 0
    private var zoneBannerText = ""
    private var zoneBannerTimer = 0f

    // Shield & Magnet power-up (triggered by mouth-open 😮 or HUD Shield button)
    private var shieldCharge = 100f
    private var shieldActiveTimer = 0f

    // Entities & VFX
    private val obstacles = ArrayList<FairWaveGenerator.SpawnedObstacle>()
    private val collectibles = ArrayList<FairWaveGenerator.SpawnedCollectible>()
    private val particles = ArrayList<Particle>()
    private val popups = ArrayList<FloatingPopup>()
    private val bgStars = ArrayList<BgStar>()
    private val bgClouds = ArrayList<BgCloud>()
    private var distanceToNextWave = 240f
    private var screenShake = 0f

    // Touch controls
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartSteer = 0f
    private val shieldBtnRect = RectF()
    private val startBtnRect = RectF()
    private val retryBtnRect = RectF()

    // Paints
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rocketPath = Path()
    private val asteroidPath = Path()

    private data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        var maxLife: Float,
        var radius: Float,
        val color: Int
    )

    private data class FloatingPopup(
        var x: Float,
        var y: Float,
        val text: String,
        val color: Int,
        var life: Float
    )

    private data class BgStar(
        var xNorm: Float,
        var yNorm: Float,
        val size: Float,
        val speedFactor: Float,
        val twinklePhase: Float
    )

    private data class BgCloud(
        var xNorm: Float,
        var yNorm: Float,
        val widthNorm: Float,
        val speedFactor: Float,
        val alpha: Int
    )

    init {
        holder.addCallback(this)
        isFocusable = true

        for (i in 0 until 65) {
            bgStars.add(
                BgStar(
                    xNorm = Math.random().toFloat(),
                    yNorm = Math.random().toFloat(),
                    size = (1.5f + Math.random().toFloat() * 3.2f),
                    speedFactor = 0.15f + Math.random().toFloat() * 0.55f,
                    twinklePhase = (Math.random() * PI * 2).toFloat()
                )
            )
        }
        for (i in 0 until 10) {
            bgClouds.add(
                BgCloud(
                    xNorm = Math.random().toFloat(),
                    yNorm = Math.random().toFloat(),
                    widthNorm = 0.22f + Math.random().toFloat() * 0.24f,
                    speedFactor = 0.25f + Math.random().toFloat() * 0.35f,
                    alpha = (45 + Math.random() * 55).toInt()
                )
            )
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        resume()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenWidth = width.toFloat()
        screenHeight = height.toFloat()
        rocketWidth = (screenWidth * 0.105f).coerceIn(76f, 110f)
        rocketHeight = rocketWidth * 1.65f
        if (rocketX == 0f || state == GameState.START) {
            rocketX = screenWidth * 0.5f
        }
        rocketY = screenHeight * 0.78f
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pause()
    }

    fun pause() {
        isRunning = false
        try {
            gameThread?.join()
        } catch (_: InterruptedException) {}
        gameThread = null
    }

    fun resume() {
        if (isRunning) return
        isRunning = true
        gameThread = Thread(this).apply { start() }
    }

    fun release() {
        pause()
        sound.release()
    }

    fun setHeadSteer(steer: Float) {
        headSteer = steer.coerceIn(-1f, 1f)
    }

    fun updateCalibrationProgress(collected: Int, target: Int, done: Boolean) {
        calibrationCollected = collected
        calibrationTarget = target
        calibrationDone = done
    }

    fun triggerMouthShield() {
        pendingActions.add {
            activateShieldIfReady()
        }
    }

    private fun activateShieldIfReady() {
        if (state != GameState.PLAYING) return
        if (shieldActiveTimer <= 0f && shieldCharge >= 100f) {
            shieldCharge = 0f
            shieldActiveTimer = 3.8f
            sound.playShieldActivate()
            popups.add(FloatingPopup(rocketX, rocketY - rocketHeight * 0.75f, "🛡️ 等离子护盾!", Color.rgb(34, 211, 238), 1.1f))
            spawnBurst(rocketX, rocketY, Color.rgb(34, 211, 238), 16)
        }
    }

    private fun beginLaunchSequence() {
        state = GameState.CALIBRATING
        altitude = 0f
        score = 0
        starsCollected = 0
        grazesCount = 0
        comboCount = 0
        peakSpeedMult = 1.0f
        currentZoneIndex = 0
        zoneBannerTimer = 0f
        shieldCharge = 100f
        shieldActiveTimer = 0f
        screenShake = 0f

        obstacles.clear()
        collectibles.clear()
        particles.clear()
        popups.clear()
        waveGenerator.reset()

        headSteer = 0f
        touchSteer = 0f
        activeSteer = 0f
        rocketX = screenWidth * 0.5f
        rocketRollDeg = 0f

        countdownTimer = if (isFaceControlEnabled) 2.25f else 1.50f
        lastCountdownDigit = 4

        pendingActions.clear()
        post {
            onStartCalibrationRequested?.invoke()
            onGameStateChanged?.invoke(GameState.CALIBRATING)
        }
    }

    private fun launchPlay() {
        state = GameState.PLAYING
        worldSpeed = baseWorldSpeed()
        distanceToNextWave = screenHeight * 0.18f
        sound.playCountdownTick(isLaunch = true)
        spawnBurst(rocketX, rocketY + rocketHeight * 0.45f, Color.rgb(255, 165, 0), 22)

        // Pre-populate 2 initial fair waves near top
        spawnWave(spawnY = -rocketWidth)
        spawnWave(spawnY = -screenHeight * 0.44f)

        post {
            onFinalizeCalibrationRequested?.invoke()
            onGameStateChanged?.invoke(GameState.PLAYING)
        }
    }

    private fun baseWorldSpeed(): Float {
        val h = screenHeight.coerceAtLeast(1000f)
        return if (isFaceControlEnabled) h * 0.29f else h * 0.35f
    }

    private fun maxWorldSpeed(): Float {
        val h = screenHeight.coerceAtLeast(1000f)
        return if (isFaceControlEnabled) h * 0.62f else h * 0.80f
    }

    private fun gameOver() {
        state = GameState.GAMEOVER
        screenShake = 18f
        sound.playCrash()
        spawnBurst(rocketX, rocketY, Color.rgb(255, 90, 40), 28)
        spawnBurst(rocketX, rocketY, Color.rgb(250, 204, 21), 16)

        val altInt = altitude.toInt()
        if (score > bestScore) {
            bestScore = score
            prefs.edit().putInt("best_score", bestScore).apply()
        }
        if (altInt > bestAltitude) {
            bestAltitude = altInt
            prefs.edit().putInt("best_altitude", bestAltitude).apply()
        }
        post { onGameStateChanged?.invoke(GameState.GAMEOVER) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state == GameState.START) {
                    if (startBtnRect.isEmpty || startBtnRect.contains(x, y) || y > screenHeight * 0.35f) {
                        beginLaunchSequence()
                    }
                    return true
                }
                if (state == GameState.GAMEOVER) {
                    if (retryBtnRect.isEmpty || retryBtnRect.contains(x, y) || y > screenHeight * 0.52f) {
                        beginLaunchSequence()
                    }
                    return true
                }
                if (state == GameState.PLAYING) {
                    if (shieldBtnRect.contains(x, y)) {
                        activateShieldIfReady()
                        return true
                    }
                    if (!isFaceControlEnabled) {
                        isDragging = true
                        dragStartX = x
                        dragStartSteer = touchSteer
                    }
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (state == GameState.PLAYING && !isFaceControlEnabled && isDragging) {
                    val dragSpan = (screenWidth * 0.36f).coerceAtLeast(140f)
                    val dx = x - dragStartX
                    touchSteer = (dragStartSteer + dx / dragSpan).coerceIn(-1f, 1f)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }

    override fun run() {
        var lastNs = System.nanoTime()
        while (isRunning) {
            val nowNs = System.nanoTime()
            val dt = ((nowNs - lastNs) / 1_000_000_000f).coerceIn(0.004f, 0.05f)
            lastNs = nowNs

            update(dt)
            drawFrame()

            val elapsedMs = (System.nanoTime() - nowNs) / 1_000_000L
            val sleepMs = 16L - elapsedMs
            if (sleepMs > 0L) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {}
            }
        }
    }

    private fun update(dt: Float) {
        if (screenWidth <= 0f || screenHeight <= 0f) return

        while (true) {
            val action = pendingActions.poll() ?: break
            action()
        }

        if (screenShake > 0f) {
            screenShake = max(0f, screenShake - dt * 42f)
        }

        if (state == GameState.CALIBRATING) {
            val stepDur = if (isFaceControlEnabled) 0.75f else 0.50f
            countdownTimer -= dt
            val digit = ceil((countdownTimer / stepDur).toDouble()).toInt().coerceIn(0, 3)
            if (digit in 1..3 && digit < lastCountdownDigit) {
                lastCountdownDigit = digit
                sound.playCountdownTick(isLaunch = false)
            }
            // Idle thruster sparks on launchpad
            if (Math.random() < 0.75) {
                particles.add(
                    Particle(
                        x = rocketX + (Math.random().toFloat() - 0.5f) * rocketWidth * 0.22f,
                        y = rocketY + rocketHeight * 0.44f,
                        vx = (Math.random().toFloat() - 0.5f) * 70f,
                        vy = 110f + Math.random().toFloat() * 110f,
                        life = 0.45f,
                        maxLife = 0.45f,
                        radius = 4f + Math.random().toFloat() * 5f,
                        color = if (Math.random() < 0.5) Color.rgb(255, 175, 40) else Color.rgb(203, 213, 225)
                    )
                )
            }
            updateParticles(dt)
            if (countdownTimer <= 0f) {
                launchPlay()
            }
            return
        }

        if (state != GameState.PLAYING) {
            updateParticles(dt)
            return
        }

        // 1. Progressive velocity & altitude progression
        val baseSpd = baseWorldSpeed()
        val maxSpd = maxWorldSpeed()
        val progress = (altitude / (if (isFaceControlEnabled) 4500f else 3200f)).coerceIn(0f, 1f)
        worldSpeed = baseSpd + (maxSpd - baseSpd) * progress.pow(0.75f)
        peakSpeedMult = max(peakSpeedMult, worldSpeed / baseSpd)

        val altGain = (worldSpeed / screenHeight.coerceAtLeast(1000f)) * 120f * dt
        altitude += altGain
        score = altitude.toInt() + starsCollected * 15 + grazesCount * 25

        // Zone milestones
        val newZone = when {
            altitude < 750f -> 0
            altitude < 1900f -> 1
            altitude < 3600f -> 2
            else -> 3
        }
        if (newZone > currentZoneIndex) {
            currentZoneIndex = newZone
            sound.playZoneUp()
            zoneBannerText = when (newZone) {
                1 -> "🌅 进入平流层晚霞 · 速度上升"
                2 -> "🌌 进入中间层极光 · 陨石来袭"
                else -> "🪐 冲入深邃星海 · 极限闪避"
            }
            zoneBannerTimer = 2.6f
        }
        if (zoneBannerTimer > 0f) {
            zoneBannerTimer = max(0f, zoneBannerTimer - dt)
        }

        // Shield charge & active duration
        if (shieldActiveTimer > 0f) {
            shieldActiveTimer = max(0f, shieldActiveTimer - dt)
        } else {
            shieldCharge = (shieldCharge + dt * 6.5f).coerceAtMost(100f)
        }

        // 2. Proportional steering & rocket kinematics
        activeSteer = if (isFaceControlEnabled) headSteer else touchSteer
        val marginX = rocketWidth * 0.56f
        val targetX = (screenWidth * (0.5f + activeSteer * 0.45f)).coerceIn(marginX, screenWidth - marginX)

        val prevX = rocketX
        val followRate = if (isFaceControlEnabled) 13.5f else 18.0f
        val alpha = 1f - exp(-followRate * dt)
        rocketX += (targetX - rocketX) * alpha

        val vx = (rocketX - prevX) / dt
        val targetRoll = ((vx / (screenWidth * 0.85f)) * 24f + activeSteer * 5f).coerceIn(-22f, 22f)
        val rollAlpha = 1f - exp(-12f * dt)
        rocketRollDeg += (targetRoll - rocketRollDeg) * rollAlpha

        // Thruster exhaust particles (angled with rocket roll)
        val rad = Math.toRadians(rocketRollDeg.toDouble())
        val exhaustX = rocketX - sin(rad).toFloat() * (rocketHeight * 0.46f)
        val exhaustY = rocketY + cos(rad).toFloat() * (rocketHeight * 0.46f)
        val flameCount = if (shieldActiveTimer > 0f) 3 else 2
        for (i in 0 until flameCount) {
            val spread = (Math.random().toFloat() - 0.5f) * 90f
            val jetVx = -sin(rad).toFloat() * 190f + spread
            val jetVy = cos(rad).toFloat() * (240f + worldSpeed * 0.25f)
            val color = when {
                shieldActiveTimer > 0f -> if (Math.random() < 0.5) Color.rgb(34, 211, 238) else Color.rgb(165, 243, 252)
                Math.random() < 0.45 -> Color.rgb(254, 240, 138)
                else -> Color.rgb(255, 106, 0)
            }
            particles.add(
                Particle(
                    x = exhaustX + (Math.random().toFloat() - 0.5f) * rocketWidth * 0.16f,
                    y = exhaustY,
                    vx = jetVx,
                    vy = jetVy,
                    life = 0.28f,
                    maxLife = 0.28f,
                    radius = (4.5f + Math.random().toFloat() * 5f),
                    color = color
                )
            )
        }

        // 3. Parallax sky clouds & stars
        for (s in bgStars) {
            s.yNorm += (worldSpeed * s.speedFactor * 0.45f / screenHeight) * dt
            if (s.yNorm > 1.05f) {
                s.yNorm = -0.05f
                s.xNorm = Math.random().toFloat()
            }
        }
        for (c in bgClouds) {
            c.yNorm += (worldSpeed * c.speedFactor / screenHeight) * dt
            if (c.yNorm > 1.15f) {
                c.yNorm = -0.15f
                c.xNorm = Math.random().toFloat()
            }
        }

        // 4. Wave Spawner
        distanceToNextWave -= worldSpeed * dt
        if (distanceToNextWave <= 0f) {
            spawnWave(spawnY = -rocketHeight)
            val waveGapBase = if (isFaceControlEnabled) screenHeight * 0.34f else screenHeight * 0.29f
            val speedBonusGap = (worldSpeed - baseSpd) * 0.22f
            distanceToNextWave = waveGapBase + speedBonusGap + (Math.random().toFloat() * screenHeight * 0.05f)
        }

        // 5. Obstacles update, fair core hitbox collision, & near-miss graze
        val coreRadius = rocketWidth * 0.27f
        val grazeRadius = rocketWidth * 0.80f
        val obsIter = obstacles.iterator()
        while (obsIter.hasNext()) {
            val ob = obsIter.next()
            if (ob.destroyed) {
                obsIter.remove()
                continue
            }

            if (ob.kind == FairWaveGenerator.ObstacleKind.WARNING_METEOR && ob.warningTimer > 0f) {
                ob.warningTimer -= dt
                continue
            }

            ob.y += worldSpeed * ob.speedScale * dt
            ob.rotation += ob.rotSpeed * dt

            if (ob.kind == FairWaveGenerator.ObstacleKind.SATELLITE) {
                ob.x += ob.vx * dt
                if (ob.x <= ob.patrolMinX) {
                    ob.x = ob.patrolMinX
                    ob.vx = abs(ob.vx)
                } else if (ob.x >= ob.patrolMaxX) {
                    ob.x = ob.patrolMaxX
                    ob.vx = -abs(ob.vx)
                }
            }

            val dx = ob.x - rocketX
            val dy = ob.y - rocketY
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

            if (dist < coreRadius + ob.radius * 0.84f) {
                if (shieldActiveTimer > 0f) {
                    ob.destroyed = true
                    score += 40
                    sound.playShieldShatter()
                    spawnBurst(ob.x, ob.y, Color.rgb(34, 211, 238), 16)
                    popups.add(FloatingPopup(ob.x, ob.y, "🛡️ +40", Color.rgb(103, 232, 249), 0.9f))
                    obsIter.remove()
                    continue
                } else {
                    gameOver()
                    return
                }
            }

            // Near-miss Graze detection
            if (!ob.grazed && dist < grazeRadius + ob.radius * 0.85f) {
                ob.grazed = true
                grazesCount++
                shieldCharge = (shieldCharge + 14f).coerceAtMost(100f)
                sound.playGraze()
                popups.add(FloatingPopup(rocketX, rocketY - rocketHeight * 0.6f, "⚡ 擦边 +25", Color.rgb(250, 204, 21), 0.85f))
                spawnBurst((rocketX + ob.x) * 0.5f, (rocketY + ob.y) * 0.5f, Color.rgb(250, 204, 21), 8)
            }

            if (ob.y > screenHeight + ob.radius * 2.5f) {
                obsIter.remove()
            }
        }

        // 6. Collectibles update (+ magnetic pull when shield active!)
        val pickupRadius = rocketWidth * 0.56f
        val colIter = collectibles.iterator()
        while (colIter.hasNext()) {
            val col = colIter.next()
            col.y += worldSpeed * dt
            col.pulseOffset += dt * 5f

            if (shieldActiveTimer > 0f) {
                val dx = rocketX - col.x
                val dy = rocketY - col.y
                val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (d < screenWidth * 0.48f && d > 1f) {
                    val pull = (screenWidth * 1.15f) * dt
                    col.x += (dx / d) * pull
                    col.y += (dy / d) * pull
                }
            }

            val dist = hypot((col.x - rocketX).toDouble(), (col.y - rocketY).toDouble()).toFloat()
            if (!col.collected && dist < pickupRadius + col.radius) {
                col.collected = true
                if (col.kind == FairWaveGenerator.CollectibleKind.FUEL) {
                    starsCollected += 2
                    shieldCharge = (shieldCharge + 30f).coerceAtMost(100f)
                    sound.playFuelPickup()
                    popups.add(FloatingPopup(col.x, col.y, "🔋 燃料 +30", Color.rgb(74, 222, 128), 0.9f))
                    spawnBurst(col.x, col.y, Color.rgb(74, 222, 128), 10)
                } else {
                    starsCollected++
                    comboCount++
                    shieldCharge = (shieldCharge + 8f).coerceAtMost(100f)
                    sound.playStarPickup(comboCount)
                    popups.add(FloatingPopup(col.x, col.y, "★ +15", Color.rgb(254, 240, 138), 0.75f))
                    spawnBurst(col.x, col.y, Color.rgb(250, 204, 21), 8)
                }
            }

            if (col.collected || col.y > screenHeight + 60f) {
                colIter.remove()
            }
        }

        updateParticles(dt)
    }

    private fun spawnWave(spawnY: Float) {
        val wave = waveGenerator.generateWave(
            screenWidth = screenWidth,
            rocketWidth = rocketWidth,
            spawnY = spawnY,
            altitudeMeters = altitude,
            faceMode = isFaceControlEnabled
        )
        obstacles.addAll(wave.obstacles)
        collectibles.addAll(wave.collectibles)
    }

    private fun spawnBurst(x: Float, y: Float, color: Int, count: Int) {
        for (i in 0 until count) {
            val angle = Math.random() * PI * 2
            val spd = 90f + Math.random().toFloat() * 260f
            particles.add(
                Particle(
                    x = x,
                    y = y,
                    vx = cos(angle).toFloat() * spd,
                    vy = sin(angle).toFloat() * spd,
                    life = 0.45f,
                    maxLife = 0.45f,
                    radius = 3.5f + Math.random().toFloat() * 5.5f,
                    color = color
                )
            )
        }
    }

    private fun updateParticles(dt: Float) {
        val pIter = particles.iterator()
        while (pIter.hasNext()) {
            val p = pIter.next()
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.life -= dt
            if (p.life <= 0f) pIter.remove()
        }
        val popIter = popups.iterator()
        while (popIter.hasNext()) {
            val pop = popIter.next()
            pop.y -= 55f * dt
            pop.life -= dt
            if (pop.life <= 0f) popIter.remove()
        }
    }

    private fun drawFrame() {
        val canvas = holder.lockCanvas() ?: return
        try {
            render(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun render(canvas: Canvas) {
        if (screenWidth <= 0f || screenHeight <= 0f) return

        canvas.save()
        if (screenShake > 0f) {
            val sx = ((Math.random() - 0.5) * screenShake).toFloat()
            val sy = ((Math.random() - 0.5) * screenShake).toFloat()
            canvas.translate(sx, sy)
        }

        drawAtmosphericBackground(canvas)
        drawWarningRails(canvas)
        drawCollectibles(canvas)
        drawObstacles(canvas)
        drawParticles(canvas)
        drawRocket(canvas)
        drawPopups(canvas)

        canvas.restore()

        when (state) {
            GameState.START -> drawStartScreen(canvas)
            GameState.CALIBRATING -> drawCountdownOverlay(canvas)
            GameState.PLAYING -> drawPlayingHud(canvas)
            GameState.GAMEOVER -> drawGameOverScreen(canvas)
        }
    }

    private fun drawAtmosphericBackground(canvas: Canvas) {
        val alt = altitude
        val (topColor, bottomColor, starAlpha, cloudAlpha) = when {
            alt < 750f -> {
                val t = (alt / 750f).coerceIn(0f, 1f)
                Quad(
                    blendColor(Color.rgb(14, 116, 204), Color.rgb(30, 58, 138), t),
                    blendColor(Color.rgb(125, 211, 252), Color.rgb(251, 146, 60), t),
                    (t * 70f).toInt(),
                    1f - t * 0.35f
                )
            }
            alt < 1900f -> {
                val t = ((alt - 750f) / 1150f).coerceIn(0f, 1f)
                Quad(
                    blendColor(Color.rgb(30, 58, 138), Color.rgb(15, 23, 42), t),
                    blendColor(Color.rgb(251, 146, 60), Color.rgb(109, 40, 217), t),
                    (70f + t * 135f).toInt(),
                    (1f - t) * 0.65f
                )
            }
            alt < 3600f -> {
                val t = ((alt - 1900f) / 1700f).coerceIn(0f, 1f)
                Quad(
                    blendColor(Color.rgb(15, 23, 42), Color.rgb(3, 7, 18), t),
                    blendColor(Color.rgb(109, 40, 217), Color.rgb(17, 24, 39), t),
                    (205f + t * 50f).toInt(),
                    0f
                )
            }
            else -> Quad(Color.rgb(3, 7, 18), Color.rgb(12, 18, 38), 255, 0f)
        }

        paint.shader = LinearGradient(0f, 0f, 0f, screenHeight, topColor, bottomColor, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, screenWidth, screenHeight, paint)
        paint.shader = null

        // Stars
        if (starAlpha > 5) {
            for (s in bgStars) {
                val twinkle = 0.65f + 0.35f * sin(s.twinklePhase + altitude * 0.03f)
                paint.color = Color.argb((starAlpha * twinkle).toInt().coerceIn(0, 255), 241, 245, 249)
                canvas.drawCircle(s.xNorm * screenWidth, s.yNorm * screenHeight, s.size, paint)
            }
        }

        // Aurora glow band in Mesosphere
        if (alt in 1700f..3900f) {
            val auroraStrength = sin(((alt - 1700f) / 2200f) * PI).toFloat().coerceIn(0f, 1f)
            paint.shader = LinearGradient(
                0f, screenHeight * 0.1f, screenWidth, screenHeight * 0.55f,
                Color.argb((auroraStrength * 65f).toInt(), 16, 185, 129),
                Color.argb((auroraStrength * 55f).toInt(), 34, 211, 238),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, screenWidth, screenHeight, paint)
            paint.shader = null
        }

        // Parallax fluffy clouds in Troposphere / Stratosphere
        if (cloudAlpha > 0.02f) {
            for (c in bgClouds) {
                val cx = c.xNorm * screenWidth
                val cy = c.yNorm * screenHeight
                val cw = c.widthNorm * screenWidth
                val ch = cw * 0.35f
                paint.color = Color.argb((c.alpha * cloudAlpha).toInt().coerceIn(0, 140), 255, 255, 255)
                canvas.drawOval(cx - cw * 0.5f, cy - ch * 0.5f, cx + cw * 0.5f, cy + ch * 0.5f, paint)
                canvas.drawOval(cx - cw * 0.3f, cy - ch * 0.8f, cx + cw * 0.3f, cy + ch * 0.25f, paint)
            }
        }

        // Launchpad ground receding at very low altitude
        if (alt < 120f) {
            val padOffset = (alt / 120f) * screenHeight * 0.32f
            val padTop = screenHeight * 0.88f + padOffset
            paint.color = Color.rgb(30, 41, 59)
            canvas.drawRect(0f, padTop, screenWidth, screenHeight + padOffset, paint)
            paint.color = Color.rgb(245, 158, 11)
            canvas.drawRect(screenWidth * 0.25f, padTop, screenWidth * 0.75f, padTop + 10f, paint)
        }
    }

    private fun drawWarningRails(canvas: Canvas) {
        for (ob in obstacles) {
            if (ob.kind == FairWaveGenerator.ObstacleKind.WARNING_METEOR && ob.warningTimer > 0f) {
                val flash = (0.45f + 0.55f * abs(sin(ob.warningTimer * 18f)))
                strokePaint.color = Color.argb((flash * 210f).toInt(), 248, 113, 113)
                strokePaint.strokeWidth = 4f
                strokePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 14f), 0f)
                canvas.drawLine(ob.x, 0f, ob.x, screenHeight, strokePaint)
                strokePaint.pathEffect = null

                paint.color = Color.argb((flash * 42f).toInt(), 239, 68, 68)
                canvas.drawRect(ob.x - ob.radius, 0f, ob.x + ob.radius, screenHeight, paint)

                // Top warning badge
                paint.color = Color.rgb(220, 38, 38)
                canvas.drawCircle(ob.x, 210f, 22f, paint)
                textPaint.color = Color.WHITE
                textPaint.textSize = 26f
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("!", ob.x, 219f, textPaint)
            }
        }
    }

    private fun drawCollectibles(canvas: Canvas) {
        for (col in collectibles) {
            val pulse = 1f + 0.14f * sin(col.pulseOffset)
            val r = col.radius * pulse
            if (col.kind == FairWaveGenerator.CollectibleKind.FUEL) {
                paint.color = Color.argb(70, 74, 222, 128)
                canvas.drawCircle(col.x, col.y, r * 1.45f, paint)
                paint.color = Color.rgb(34, 197, 94)
                canvas.drawRoundRect(col.x - r * 0.72f, col.y - r, col.x + r * 0.72f, col.y + r, 10f, 10f, paint)
                paint.color = Color.rgb(187, 247, 208)
                canvas.drawRect(col.x - r * 0.35f, col.y - r * 1.18f, col.x + r * 0.35f, col.y - r * 0.9f, paint)
                textPaint.color = Color.WHITE
                textPaint.textSize = r * 0.95f
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("⚡", col.x, col.y + r * 0.34f, textPaint)
            } else {
                paint.color = Color.argb(65, 250, 204, 21)
                canvas.drawCircle(col.x, col.y, r * 1.4f, paint)
                paint.color = Color.rgb(250, 204, 21)
                canvas.drawCircle(col.x, col.y, r, paint)
                paint.color = Color.rgb(254, 249, 195)
                canvas.drawCircle(col.x, col.y, r * 0.62f, paint)
            }
        }
    }

    private fun drawObstacles(canvas: Canvas) {
        for (ob in obstacles) {
            if (ob.kind == FairWaveGenerator.ObstacleKind.WARNING_METEOR && ob.warningTimer > 0f) continue

            canvas.save()
            canvas.translate(ob.x, ob.y)
            canvas.rotate(ob.rotation)

            when (ob.kind) {
                FairWaveGenerator.ObstacleKind.ASTEROID -> {
                    val r = ob.radius
                    asteroidPath.reset()
                    val pts = 8
                    for (i in 0 until pts) {
                        val a = (i.toFloat() / pts) * PI * 2
                        val jitter = if (i % 2 == 0) 1.0f else 0.78f
                        val px = cos(a).toFloat() * r * jitter
                        val py = sin(a).toFloat() * r * jitter
                        if (i == 0) asteroidPath.moveTo(px, py) else asteroidPath.lineTo(px, py)
                    }
                    asteroidPath.close()

                    paint.color = Color.rgb(100, 116, 139)
                    canvas.drawPath(asteroidPath, paint)
                    strokePaint.color = Color.rgb(148, 163, 184)
                    strokePaint.strokeWidth = 3f
                    canvas.drawPath(asteroidPath, strokePaint)

                    paint.color = Color.rgb(51, 65, 85)
                    canvas.drawCircle(-r * 0.25f, -r * 0.2f, r * 0.22f, paint)
                    canvas.drawCircle(r * 0.28f, r * 0.22f, r * 0.16f, paint)
                }
                FairWaveGenerator.ObstacleKind.SATELLITE -> {
                    val r = ob.radius
                    // Solar wings
                    paint.color = Color.rgb(37, 99, 235)
                    canvas.drawRect(-r * 1.2f, -r * 0.24f, -r * 0.42f, r * 0.24f, paint)
                    canvas.drawRect(r * 0.42f, -r * 0.24f, r * 1.2f, r * 0.24f, paint)
                    // Metallic core
                    paint.color = Color.rgb(226, 232, 240)
                    canvas.drawRoundRect(-r * 0.46f, -r * 0.48f, r * 0.46f, r * 0.48f, 10f, 10f, paint)
                    // Blinking beacon
                    paint.color = Color.rgb(248, 113, 113)
                    canvas.drawCircle(0f, -r * 0.18f, r * 0.16f, paint)
                }
                FairWaveGenerator.ObstacleKind.WARNING_METEOR -> {
                    val r = ob.radius
                    paint.color = Color.argb(120, 249, 115, 22)
                    canvas.drawOval(-r * 0.9f, -r * 2.8f, r * 0.9f, r * 0.9f, paint)
                    paint.color = Color.rgb(239, 68, 68)
                    canvas.drawCircle(0f, 0f, r, paint)
                    paint.color = Color.rgb(254, 240, 138)
                    canvas.drawCircle(0f, 0f, r * 0.52f, paint)
                }
            }
            canvas.restore()
        }
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in particles) {
            val alpha = ((p.life / p.maxLife) * 255f).toInt().coerceIn(0, 255)
            paint.color = Color.argb(alpha, Color.red(p.color), Color.green(p.color), Color.blue(p.color))
            canvas.drawCircle(p.x, p.y, p.radius * (0.4f + 0.6f * (p.life / p.maxLife)), paint)
        }
    }

    private fun drawRocket(canvas: Canvas) {
        canvas.save()
        canvas.translate(rocketX, rocketY)
        canvas.rotate(rocketRollDeg)

        val w = rocketWidth
        val h = rocketHeight

        // Shield energy aura when active
        if (shieldActiveTimer > 0f) {
            val pulse = 1f + 0.08f * sin(shieldActiveTimer * 14f)
            paint.color = Color.argb(55, 34, 211, 238)
            canvas.drawCircle(0f, 0f, w * 0.92f * pulse, paint)
            strokePaint.color = Color.rgb(34, 211, 238)
            strokePaint.strokeWidth = 4.5f
            canvas.drawCircle(0f, 0f, w * 0.92f * pulse, strokePaint)
        }

        // Main thruster cone
        val flicker = 0.85f + Math.random().toFloat() * 0.35f
        paint.color = Color.rgb(255, 106, 0)
        rocketPath.reset()
        rocketPath.moveTo(-w * 0.19f, h * 0.38f)
        rocketPath.lineTo(0f, h * 0.38f + h * 0.32f * flicker)
        rocketPath.lineTo(w * 0.19f, h * 0.38f)
        rocketPath.close()
        canvas.drawPath(rocketPath, paint)

        paint.color = Color.rgb(254, 240, 138)
        rocketPath.reset()
        rocketPath.moveTo(-w * 0.10f, h * 0.38f)
        rocketPath.lineTo(0f, h * 0.38f + h * 0.20f * flicker)
        rocketPath.lineTo(w * 0.10f, h * 0.38f)
        rocketPath.close()
        canvas.drawPath(rocketPath, paint)

        // Side stabilizer fins
        paint.color = Color.rgb(239, 68, 68)
        rocketPath.reset()
        rocketPath.moveTo(-w * 0.24f, h * 0.14f)
        rocketPath.lineTo(-w * 0.48f, h * 0.40f)
        rocketPath.lineTo(-w * 0.22f, h * 0.36f)
        rocketPath.close()
        canvas.drawPath(rocketPath, paint)

        rocketPath.reset()
        rocketPath.moveTo(w * 0.24f, h * 0.14f)
        rocketPath.lineTo(w * 0.48f, h * 0.40f)
        rocketPath.lineTo(w * 0.22f, h * 0.36f)
        rocketPath.close()
        canvas.drawPath(rocketPath, paint)

        // Sleek fuselage body
        paint.color = Color.rgb(248, 250, 252)
        rocketPath.reset()
        rocketPath.moveTo(0f, -h * 0.50f)
        rocketPath.cubicTo(w * 0.28f, -h * 0.24f, w * 0.30f, h * 0.10f, w * 0.24f, h * 0.38f)
        rocketPath.lineTo(-w * 0.24f, h * 0.38f)
        rocketPath.cubicTo(-w * 0.30f, h * 0.10f, -w * 0.28f, -h * 0.24f, 0f, -h * 0.50f)
        rocketPath.close()
        canvas.drawPath(rocketPath, paint)

        // Nose cone accent
        canvas.save()
        canvas.clipPath(rocketPath)
        paint.color = Color.rgb(239, 68, 68)
        canvas.drawRect(-w * 0.4f, -h * 0.52f, w * 0.4f, -h * 0.20f, paint)
        canvas.restore()

        // Cockpit porthole
        paint.color = Color.rgb(15, 23, 42)
        canvas.drawCircle(0f, -h * 0.04f, w * 0.16f, paint)
        paint.color = Color.rgb(34, 211, 238)
        canvas.drawCircle(0f, -h * 0.04f, w * 0.115f, paint)

        canvas.restore()
    }

    private fun drawPopups(canvas: Canvas) {
        for (pop in popups) {
            val alpha = (pop.life * 255f).toInt().coerceIn(0, 255)
            textPaint.color = Color.argb(alpha, Color.red(pop.color), Color.green(pop.color), Color.blue(pop.color))
            textPaint.textSize = (screenHeight * 0.022f).coerceIn(28f, 44f)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(pop.text, pop.x, pop.y, textPaint)
        }
    }

    private fun drawPlayingHud(canvas: Canvas) {
        val topPad = screenHeight * 0.045f

        // Altitude & score
        textPaint.color = Color.WHITE
        textPaint.textSize = screenHeight * 0.036f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("${altitude.toInt()}m", 40f, topPad + 44f, textPaint)

        val zoneLabel = when (currentZoneIndex) {
            0 -> "☁ 对流层"
            1 -> "🌅 平流层"
            2 -> "🌌 极光层"
            else -> "🪐 深空"
        }
        val mult = String.format("%.1fx", worldSpeed / baseWorldSpeed())
        textPaint.textSize = screenHeight * 0.017f
        val subHud = "$zoneLabel · $mult · ★$starsCollected"
        val badgeW = textPaint.measureText(subHud) + 36f
        paint.color = Color.argb(145, 15, 23, 42)
        canvas.drawRoundRect(40f, topPad + 58f, 40f + badgeW, topPad + 98f, 20f, 20f, paint)
        textPaint.color = Color.rgb(226, 232, 240)
        canvas.drawText(subHud, 58f, topPad + 84f, textPaint)

        // Minimalist Real-Time Steering Gauge (◀ ——●—— ▶)
        drawSteerGauge(canvas, screenWidth * 0.5f, topPad + 96f)

        // Zone Milestone Banner
        if (zoneBannerTimer > 0f) {
            val bannerAlpha = (min(1f, zoneBannerTimer) * 220f).toInt()
            paint.color = Color.argb(bannerAlpha, 15, 23, 42)
            val bw = screenWidth * 0.72f
            canvas.drawRoundRect(screenWidth * 0.5f - bw * 0.5f, screenHeight * 0.24f, screenWidth * 0.5f + bw * 0.5f, screenHeight * 0.24f + 68f, 34f, 34f, paint)
            textPaint.color = Color.argb(bannerAlpha, 250, 204, 21)
            textPaint.textSize = screenHeight * 0.021f
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(zoneBannerText, screenWidth * 0.5f, screenHeight * 0.24f + 43f, textPaint)
        }

        // Bottom-left / Bottom-center Shield Power-up Pill
        val pillW = (screenWidth * 0.44f).coerceIn(280f, 440f)
        val pillH = 74f
        val pillLeft = screenWidth * 0.5f - pillW * 0.5f
        val pillTop = screenHeight - pillH - 34f
        shieldBtnRect.set(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)

        val shieldReady = shieldCharge >= 100f
        paint.color = when {
            shieldActiveTimer > 0f -> Color.argb(210, 8, 145, 178)
            shieldReady -> Color.argb(200, 14, 116, 144)
            else -> Color.argb(150, 15, 23, 42)
        }
        canvas.drawRoundRect(shieldBtnRect, 37f, 37f, paint)

        if (shieldActiveTimer <= 0f) {
            val fillFrac = (shieldCharge / 100f).coerceIn(0f, 1f)
            paint.color = Color.argb(165, 34, 211, 238)
            canvas.drawRoundRect(pillLeft, pillTop, pillLeft + pillW * fillFrac, pillTop + pillH, 37f, 37f, paint)
        }

        strokePaint.color = if (shieldReady || shieldActiveTimer > 0f) Color.rgb(103, 232, 249) else Color.argb(110, 148, 163, 184)
        strokePaint.strokeWidth = 2.5f
        canvas.drawRoundRect(shieldBtnRect, 37f, 37f, strokePaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = screenHeight * 0.018f
        textPaint.textAlign = Paint.Align.CENTER
        val shieldLabel = when {
            shieldActiveTimer > 0f -> String.format("🛡️ 护盾激活 %.1fs", shieldActiveTimer)
            shieldReady && isFaceControlEnabled -> "😮 张嘴 / 点按开启护盾"
            shieldReady -> "🛡️ 点击开启等离子护盾"
            else -> "🔋 护盾充能 ${shieldCharge.toInt()}%"
        }
        canvas.drawText(shieldLabel, shieldBtnRect.centerX(), shieldBtnRect.centerY() + 10f, textPaint)
    }

    private fun drawSteerGauge(canvas: Canvas, cx: Float, cy: Float) {
        val gaugeW = (screenWidth * 0.42f).coerceIn(220f, 360f)
        val halfW = gaugeW * 0.5f

        paint.color = Color.argb(135, 15, 23, 42)
        canvas.drawRoundRect(cx - halfW - 28f, cy - 16f, cx + halfW + 28f, cy + 16f, 16f, 16f, paint)

        // Track line
        strokePaint.color = Color.argb(130, 148, 163, 184)
        strokePaint.strokeWidth = 3f
        canvas.drawLine(cx - halfW, cy, cx + halfW, cy, strokePaint)

        // Center zero notch
        strokePaint.color = Color.argb(200, 226, 232, 240)
        strokePaint.strokeWidth = 3f
        canvas.drawLine(cx, cy - 8f, cx, cy + 8f, strokePaint)

        // Arrows
        textPaint.color = Color.argb(175, 148, 163, 184)
        textPaint.textSize = 20f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("◀", cx - halfW - 14f, cy + 7f, textPaint)
        canvas.drawText("▶", cx + halfW + 14f, cy + 7f, textPaint)

        // Active steer knob
        val knobX = cx + activeSteer * (halfW - 6f)
        paint.color = if (isFaceControlEnabled) Color.rgb(34, 211, 238) else Color.rgb(250, 204, 21)
        canvas.drawCircle(knobX, cy, 9f, paint)
    }

    private fun drawCountdownOverlay(canvas: Canvas) {
        paint.color = Color.argb(145, 3, 7, 18)
        canvas.drawRect(0f, 0f, screenWidth, screenHeight, paint)

        val stepDur = if (isFaceControlEnabled) 0.75f else 0.50f
        val digit = ceil((countdownTimer / stepDur).toDouble()).toInt().coerceIn(1, 3)

        textPaint.color = Color.rgb(250, 204, 21)
        textPaint.textSize = screenHeight * 0.12f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("$digit", screenWidth * 0.5f, screenHeight * 0.44f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = screenHeight * 0.023f
        val sub = if (isFaceControlEnabled) {
            "🎯 正在锁定零点姿态 ($calibrationCollected/$calibrationTarget 帧)"
        } else {
            "🚀 火箭点火准备 · 单指横向拖动控制方向"
        }
        canvas.drawText(sub, screenWidth * 0.5f, screenHeight * 0.52f, textPaint)

        if (isFaceControlEnabled) {
            textPaint.color = Color.rgb(148, 163, 184)
            textPaint.textSize = screenHeight * 0.017f
            canvas.drawText("保持当前最舒服的坐姿或躺姿，正视屏幕即可", screenWidth * 0.5f, screenHeight * 0.56f, textPaint)
        }
    }

    private fun drawStartScreen(canvas: Canvas) {
        paint.color = Color.argb(195, 3, 7, 18)
        canvas.drawRect(0f, 0f, screenWidth, screenHeight, paint)

        textPaint.color = Color.rgb(34, 211, 238)
        textPaint.textSize = screenHeight * 0.052f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("ROCKET DASH", screenWidth * 0.5f, screenHeight * 0.30f, textPaint)

        textPaint.color = Color.rgb(226, 232, 240)
        textPaint.textSize = screenHeight * 0.022f
        canvas.drawText("自动上升火箭 · 连续头控躲避", screenWidth * 0.5f, screenHeight * 0.36f, textPaint)

        val btnW = (screenWidth * 0.56f).coerceIn(320f, 520f)
        val btnH = screenHeight * 0.072f
        val btnY = screenHeight * 0.48f
        startBtnRect.set(screenWidth * 0.5f - btnW * 0.5f, btnY - btnH * 0.5f, screenWidth * 0.5f + btnW * 0.5f, btnY + btnH * 0.5f)
        paint.color = Color.rgb(249, 115, 22)
        canvas.drawRoundRect(startBtnRect, btnH * 0.5f, btnH * 0.5f, paint)

        textPaint.color = Color.WHITE
        textPaint.textSize = screenHeight * 0.026f
        canvas.drawText("🚀 点火起飞", startBtnRect.centerX(), startBtnRect.centerY() + textPaint.textSize * 0.34f, textPaint)

        textPaint.color = Color.rgb(103, 232, 249)
        textPaint.textSize = screenHeight * 0.019f
        if (isFaceControlEnabled) {
            canvas.drawText("📷 头控已就绪：3-2-1 倒计时自动锚定零点", screenWidth * 0.5f, screenHeight * 0.60f, textPaint)
            canvas.drawText("左右微偏头 1:1 连续滑移 · 张嘴 😮 触发护盾", screenWidth * 0.5f, screenHeight * 0.638f, textPaint)
        } else {
            canvas.drawText("👆 触屏模式：单指在屏幕任意位置横向拖动即可", screenWidth * 0.5f, screenHeight * 0.60f, textPaint)
            canvas.drawText("点顶部「开启头控」可切换免手操作模式", screenWidth * 0.5f, screenHeight * 0.638f, textPaint)
        }

        if (bestScore > 0) {
            textPaint.color = Color.rgb(74, 222, 128)
            textPaint.textSize = screenHeight * 0.019f
            canvas.drawText("🏆 最佳积分: $bestScore · 最高高度: ${bestAltitude}m", screenWidth * 0.5f, screenHeight * 0.72f, textPaint)
        }
    }

    private fun drawGameOverScreen(canvas: Canvas) {
        paint.color = Color.argb(215, 3, 7, 18)
        canvas.drawRect(0f, 0f, screenWidth, screenHeight, paint)

        textPaint.color = Color.rgb(248, 113, 113)
        textPaint.textSize = screenHeight * 0.048f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("任务结束!", screenWidth * 0.5f, screenHeight * 0.27f, textPaint)

        val cardW = screenWidth * 0.78f
        val cardTop = screenHeight * 0.32f
        val cardH = screenHeight * 0.28f
        paint.color = Color.argb(180, 15, 23, 42)
        canvas.drawRoundRect(screenWidth * 0.5f - cardW * 0.5f, cardTop, screenWidth * 0.5f + cardW * 0.5f, cardTop + cardH, 28f, 28f, paint)
        strokePaint.color = Color.argb(110, 56, 189, 248)
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(screenWidth * 0.5f - cardW * 0.5f, cardTop, screenWidth * 0.5f + cardW * 0.5f, cardTop + cardH, 28f, 28f, strokePaint)

        textPaint.textSize = screenHeight * 0.025f
        textPaint.color = Color.WHITE
        canvas.drawText("本次总分: $score", screenWidth * 0.5f, cardTop + cardH * 0.24f, textPaint)
        textPaint.color = Color.rgb(103, 232, 249)
        canvas.drawText("爬升高度: ${altitude.toInt()}m (${String.format("%.1fx", peakSpeedMult)})", screenWidth * 0.5f, cardTop + cardH * 0.44f, textPaint)
        textPaint.color = Color.rgb(250, 204, 21)
        canvas.drawText("★ 星币: $starsCollected   ⚡ 擦边: $grazesCount", screenWidth * 0.5f, cardTop + cardH * 0.64f, textPaint)
        textPaint.color = Color.rgb(74, 222, 128)
        canvas.drawText("🏆 历史最佳: $bestScore (${bestAltitude}m)", screenWidth * 0.5f, cardTop + cardH * 0.84f, textPaint)

        val btnW = (screenWidth * 0.58f).coerceIn(340f, 540f)
        val btnH = screenHeight * 0.07f
        val btnY = screenHeight * 0.68f
        retryBtnRect.set(screenWidth * 0.5f - btnW * 0.5f, btnY - btnH * 0.5f, screenWidth * 0.5f + btnW * 0.5f, btnY + btnH * 0.5f)
        paint.color = Color.rgb(249, 115, 22)
        canvas.drawRoundRect(retryBtnRect, btnH * 0.5f, btnH * 0.5f, paint)

        textPaint.color = Color.WHITE
        textPaint.textSize = screenHeight * 0.025f
        canvas.drawText("🚀 再次发射", retryBtnRect.centerX(), retryBtnRect.centerY() + textPaint.textSize * 0.34f, textPaint)
    }

    private data class Quad(val top: Int, val bottom: Int, val starAlpha: Int, val cloudAlpha: Float)

    private fun blendColor(c1: Int, c2: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * f).toInt()
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f).toInt()
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * f).toInt()
        return Color.rgb(r, g, b)
    }
}
