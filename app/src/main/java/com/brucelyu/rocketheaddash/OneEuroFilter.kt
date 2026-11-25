package com.brucelyu.rocketheaddash

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign

/**
 * One-Euro adaptive low-pass filter (Casiez et al., CHI 2012).
 * Smooths out webcam/ML Kit micro-jitter at rest while keeping cutoff high during fast head turns.
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.15f,
    private val beta: Float = 0.042f,
    private val dCutoff: Float = 1.0f
) {
    private var initialized = false
    private var lastTimeSec = 0.0
    private var xFiltered = 0f
    private var dxFiltered = 0f

    fun reset() {
        initialized = false
        lastTimeSec = 0.0
        xFiltered = 0f
        dxFiltered = 0f
    }

    fun filter(value: Float, timestampSec: Double): Float {
        if (!initialized) {
            initialized = true
            lastTimeSec = timestampSec
            xFiltered = value
            dxFiltered = 0f
            return value
        }

        val dt = (timestampSec - lastTimeSec).coerceIn(1.0 / 240.0, 0.25).toFloat()
        lastTimeSec = timestampSec

        val dx = (value - xFiltered) / dt
        val aD = alpha(dCutoff, dt)
        dxFiltered = lerp(dxFiltered, dx, aD)

        val cutoff = minCutoff + beta * abs(dxFiltered)
        val aX = alpha(cutoff, dt)
        xFiltered = lerp(xFiltered, value, aX)
        return xFiltered
    }

    private fun alpha(cutoffHz: Float, dt: Float): Float {
        val tau = (1.0 / (2.0 * PI * cutoffHz.toDouble())).toFloat()
        return 1.0f / (1.0f + tau / dt)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

/**
 * Pure-logic baseline calibration + continuous head-to-steer mapper + mouth-open shield detector.
 * Separated from Android CameraX/ML Kit so all math and edge cases are deterministic & unit-tested.
 */
class HeadSteerMapper(
    private val targetCalibrationSamples: Int = 9,
    private val fullTurnDegrees: Float = 17.5f,
    private val fullOffsetRange: Float = 0.20f,
    private val deadzone: Float = 0.045f,
    private val mouthTriggerDelta: Float = 0.16f,
    private val mouthReleaseDelta: Float = 0.09f
) {
    private val steerFilter = OneEuroFilter(minCutoff = 1.20f, beta = 0.045f, dCutoff = 1.0f)

    var isCalibrated: Boolean = false
        private set
    var calibrationCount: Int = 0
        private set

    var baseYawDeg: Float = 0f
        private set
    var baseOffsetX: Float = 0f
        private set
    var baseMouthRatio: Float = 0.18f
        private set

    private var sumYaw = 0f
    private var sumOffsetX = 0f
    private var sumMouth = 0f
    private var mouthOpenLatched = false
    private var lastMouthTriggerTimeSec = -10.0

    var currentSteer: Float = 0f
        private set

    data class Output(
        val steer: Float,
        val isCalibrated: Boolean,
        val calibrationCount: Int,
        val calibrationTarget: Int,
        val triggerShield: Boolean,
        val mouthActive: Boolean
    )

    fun beginCalibration() {
        isCalibrated = false
        calibrationCount = 0
        sumYaw = 0f
        sumOffsetX = 0f
        sumMouth = 0f
        mouthOpenLatched = false
        currentSteer = 0f
        steerFilter.reset()
    }

    /**
     * Finalizes calibration prematurely (for example, when countdown finishes with >= 1 sample).
     * If zero samples were gathered, stays uncalibrated so the first incoming frames auto-anchor.
     */
    fun finalizeCalibrationIfSamplesExist() {
        if (!isCalibrated && calibrationCount > 0) {
            baseYawDeg = sumYaw / calibrationCount
            baseOffsetX = sumOffsetX / calibrationCount
            baseMouthRatio = sumMouth / calibrationCount
            isCalibrated = true
            steerFilter.reset()
        }
    }

    fun processPose(
        yawDeg: Float,
        offsetX: Float,
        mouthRatio: Float?,
        timestampSec: Double
    ): Output {
        if (!isCalibrated) {
            sumYaw += yawDeg
            sumOffsetX += offsetX
            sumMouth += (mouthRatio ?: 0.18f)
            calibrationCount++
            baseYawDeg = sumYaw / calibrationCount
            baseOffsetX = sumOffsetX / calibrationCount
            baseMouthRatio = sumMouth / calibrationCount

            if (calibrationCount >= targetCalibrationSamples) {
                isCalibrated = true
                steerFilter.reset()
            }
            currentSteer = 0f
            return Output(
                steer = 0f,
                isCalibrated = isCalibrated,
                calibrationCount = calibrationCount,
                calibrationTarget = targetCalibrationSamples,
                triggerShield = false,
                mouthActive = false
            )
        }

        val yawNorm = (yawDeg - baseYawDeg) / fullTurnDegrees
        val offsetNorm = (offsetX - baseOffsetX) / fullOffsetRange
        val rawSteer = (yawNorm * 0.78f + offsetNorm * 0.22f).coerceIn(-1.25f, 1.25f)

        val filtered = steerFilter.filter(rawSteer, timestampSec)
        val absVal = abs(filtered)
        val mapped = if (absVal <= deadzone) {
            0f
        } else {
            sign(filtered) * ((absVal - deadzone) / (1f - deadzone))
        }
        currentSteer = mapped.coerceIn(-1f, 1f)

        var triggerShield = false
        if (mouthRatio != null) {
            val delta = mouthRatio - baseMouthRatio
            if (!mouthOpenLatched && delta >= mouthTriggerDelta) {
                mouthOpenLatched = true
                if (timestampSec - lastMouthTriggerTimeSec >= 0.45) {
                    lastMouthTriggerTimeSec = timestampSec
                    triggerShield = true
                }
            } else if (mouthOpenLatched && delta <= mouthReleaseDelta) {
                mouthOpenLatched = false
            }
        } else {
            mouthOpenLatched = false
        }

        return Output(
            steer = currentSteer,
            isCalibrated = true,
            calibrationCount = calibrationCount,
            calibrationTarget = targetCalibrationSamples,
            triggerShield = triggerShield,
            mouthActive = mouthOpenLatched
        )
    }
}
