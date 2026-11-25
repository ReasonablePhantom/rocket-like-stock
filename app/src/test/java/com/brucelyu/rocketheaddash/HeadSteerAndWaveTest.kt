package com.brucelyu.rocketheaddash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class HeadSteerAndWaveTest {

    @Test
    fun oneEuroFilterSmoothsJitterAndTracksFastTurns() {
        val filter = OneEuroFilter(minCutoff = 1.15f, beta = 0.045f, dCutoff = 1.0f)
        var t = 0.0
        var maxDeviationAtRest = 0f
        for (i in 0 until 20) {
            t += 0.033
            val noisy = if (i % 2 == 0) 0.04f else -0.04f
            val y = filter.filter(noisy, t)
            if (i > 3) {
                maxDeviationAtRest = maxOf(maxDeviationAtRest, abs(y))
            }
        }
        assertTrue("Expected jitter to stay damped, got $maxDeviationAtRest", maxDeviationAtRest < 0.03f)

        // Rapid turn should catch up promptly
        for (i in 0 until 10) {
            t += 0.033
            filter.filter(0.95f, t)
        }
        val afterTurn = filter.filter(0.95f, t + 0.033)
        assertTrue("Expected fast turn tracking > 0.85, got $afterTurn", afterTurn > 0.85f)
    }

    @Test
    fun headSteerMapperCalibratesBaselineAndDetectsMouthOpen() {
        val mapper = HeadSteerMapper(targetCalibrationSamples = 8)
        mapper.beginCalibration()

        var t = 1.0
        // Simulate slouching / tilted sitting posture during 8-frame countdown
        for (i in 0 until 8) {
            t += 0.05
            val out = mapper.processPose(yawDeg = 12f, offsetX = -0.08f, mouthRatio = 0.20f, timestampSec = t)
            if (i < 7) {
                assertFalse(out.isCalibrated)
            } else {
                assertTrue(out.isCalibrated)
                assertEquals(0f, out.steer, 0.001f)
            }
        }

        // Staying at baseline posture outputs ~0 steer
        val neutral = mapper.processPose(yawDeg = 12f, offsetX = -0.08f, mouthRatio = 0.20f, timestampSec = t + 0.05)
        assertEquals(0f, neutral.steer, 0.01f)
        assertFalse(neutral.triggerShield)

        // Turning 10 deg right from baseline steers smoothly right
        var turnedRight = neutral
        for (i in 1..12) {
            t += 0.033
            turnedRight = mapper.processPose(yawDeg = 22f, offsetX = -0.04f, mouthRatio = 0.20f, timestampSec = t)
        }
        assertTrue("Expected positive right steer, got ${turnedRight.steer}", turnedRight.steer > 0.35f)

        // Opening mouth triggers shield once until closed
        val mouthOpen1 = mapper.processPose(yawDeg = 12f, offsetX = -0.08f, mouthRatio = 0.42f, timestampSec = t + 0.5)
        assertTrue(mouthOpen1.triggerShield)
        val mouthHeld = mapper.processPose(yawDeg = 12f, offsetX = -0.08f, mouthRatio = 0.43f, timestampSec = t + 0.6)
        assertFalse(mouthHeld.triggerShield)
    }

    @Test
    fun fairWaveGeneratorAlwaysLeavesSafeReachableCorridor() {
        val gen = FairWaveGenerator(Random(42))
        val screenW = 1080f
        val rocketW = 108f

        for (faceMode in listOf(true, false)) {
            gen.reset()
            var prevNorm = 0.5f
            for (step in 0 until 600) {
                val altitude = step * 15f
                val wave = gen.generateWave(
                    screenWidth = screenW,
                    rocketWidth = rocketW,
                    spawnY = -100f,
                    altitudeMeters = altitude,
                    faceMode = faceMode
                )

                assertTrue(
                    "Corridor width ${wave.corridorWidth} < 1.9 * rocketW",
                    wave.corridorWidth >= rocketW * 1.90f
                )

                val shift = abs(gen.corridorCenterNorm - prevNorm)
                assertTrue("Corridor center shift $shift too abrupt", shift <= 0.35f)
                prevNorm = gen.corridorCenterNorm

                val safeLeft = wave.corridorCenterX - wave.corridorWidth * 0.5f
                val safeRight = wave.corridorCenterX + wave.corridorWidth * 0.5f

                for (ob in wave.obstacles) {
                    if (ob.kind == FairWaveGenerator.ObstacleKind.SATELLITE) {
                        assertTrue(
                            "Satellite patrol [${ob.patrolMinX - ob.radius}, ${ob.patrolMaxX + ob.radius}] intrudes corridor [$safeLeft, $safeRight]",
                            ob.patrolMaxX + ob.radius <= safeLeft + 1f || ob.patrolMinX - ob.radius >= safeRight - 1f
                        )
                    } else {
                        assertTrue(
                            "Obstacle [${ob.x - ob.radius}, ${ob.x + ob.radius}] intrudes corridor [$safeLeft, $safeRight]",
                            ob.x + ob.radius <= safeLeft + 1f || ob.x - ob.radius >= safeRight - 1f
                        )
                    }
                }
            }
        }
    }
}
