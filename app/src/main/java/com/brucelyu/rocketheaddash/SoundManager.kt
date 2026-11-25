package com.brucelyu.rocketheaddash

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Procedural PCM sound synthesizer adapted from First Play App.
 * Requires zero bundled audio files and produces rocket launch, graze, pickup, shield, and crash SFX.
 */
class SoundManager {

    private val executor = Executors.newSingleThreadExecutor()
    private val sampleRate = 44100

    fun playCountdownTick(isLaunch: Boolean) {
        executor.execute {
            if (isLaunch) {
                val durationMs = 340
                val numSamples = sampleRate * durationMs / 1000
                val buffer = ShortArray(numSamples)
                var phaseA = 0.0
                var phaseB = 0.0
                for (i in 0 until numSamples) {
                    val p = i.toDouble() / numSamples
                    val freqA = 440.0 + p * 520.0
                    val freqB = 660.0 + p * 780.0
                    phaseA += 2.0 * PI * freqA / sampleRate
                    phaseB += 2.0 * PI * freqB / sampleRate
                    val env = (1.0 - p * 0.45)
                    val noise = (Math.random() * 2.0 - 1.0) * 0.14 * (1.0 - p)
                    val sample = (sin(phaseA) * 0.45 + sin(phaseB) * 0.35 + noise) * env * 0.42
                    buffer[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                playPcm(buffer)
            } else {
                val durationMs = 130
                val numSamples = sampleRate * durationMs / 1000
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val p = i.toDouble() / numSamples
                    val env = 1.0 - p
                    val sample = sin(2.0 * PI * 680.0 * t) * env * 0.3
                    buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
                }
                playPcm(buffer)
            }
        }
    }

    fun playStarPickup(combo: Int = 1) {
        executor.execute {
            val durationMs = 190
            val numSamples = sampleRate * durationMs / 1000
            val buffer = ShortArray(numSamples)
            val pitchBoost = 1.0 + ((combo - 1).coerceIn(0, 8) * 0.05)

            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val freq = (if (t < 0.065) 880.0 else 1318.51) * pitchBoost
                val decay = 1.0 - (i.toDouble() / numSamples)
                val sample = sin(2.0 * PI * freq * t) * decay * 0.36
                buffer[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            playPcm(buffer)
        }
    }

    fun playFuelPickup() {
        executor.execute {
            val durationMs = 260
            val numSamples = sampleRate * durationMs / 1000
            val buffer = ShortArray(numSamples)
            var phase = 0.0
            for (i in 0 until numSamples) {
                val p = i.toDouble() / numSamples
                val freq = when {
                    p < 0.33 -> 523.25
                    p < 0.66 -> 659.25
                    else -> 1046.50
                }
                phase += 2.0 * PI * freq / sampleRate
                val decay = 1.0 - p * 0.55
                val sample = sin(phase) * decay * 0.38
                buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            playPcm(buffer)
        }
    }

    fun playGraze() {
        executor.execute {
            val durationMs = 140
            val numSamples = sampleRate * durationMs / 1000
            val buffer = ShortArray(numSamples)
            var phase = 0.0
            for (i in 0 until numSamples) {
                val p = i.toDouble() / numSamples
                val freq = 1150.0 + p * 950.0
                phase += 2.0 * PI * freq / sampleRate
                val env = (1.0 - p) * (0.4 + 0.6 * sin(p * PI))
                val sample = sin(phase) * env * 0.32
                buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            playPcm(buffer)
        }
    }

    fun playShieldActivate() {
        executor.execute {
            val durationMs = 320
            val numSamples = sampleRate * durationMs / 1000
            val buffer = ShortArray(numSamples)
            var phase = 0.0
            var subPhase = 0.0
            for (i in 0 until numSamples) {
                val p = i.toDouble() / numSamples
                val freq = 260.0 + p * 740.0
                phase += 2.0 * PI * freq / sampleRate
                subPhase += 2.0 * PI * (freq * 1.5) / sampleRate
                val env = 1.0 - p * 0.45
                val sample = (sin(phase) * 0.6 + sin(subPhase) * 0.4) * env * 0.38
                buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            playPcm(buffer)
        }
    }

    fun playShieldShatter() {
        executor.execute {
            val durationMs = 210
            val numSamples = sampleRate * durationMs / 1000
            val buffer = ShortArray(numSamples)
            var phase = 0.0
            for (i in 0 until numSamples) {
                val p = i.toDouble() / numSamples
                val freq = 620.0 - p * 380.0
                phase += 2.0 * PI * freq / sampleRate
                val noise = (Math.random() * 2.0 - 1.0) * 0.3
                val sample = (sin(phase) * 0.7 + noise) * (1.0 - p) * 0.42
                buffer[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            playPcm(buffer)
        }
    }

    fun playZoneUp() {
        executor.execute {
            val durationMs = 360
            val numSamples = sampleRate * durationMs / 1000
            val buffer = ShortArray(numSamples)
            var phase = 0.0
            for (i in 0 until numSamples) {
                val p = i.toDouble() / numSamples
                val freq = 420.0 + p * 640.0
                phase += 2.0 * PI * freq / sampleRate
                val env = 1.0 - p * 0.35
                val sample = sin(phase) * env * 0.34
                buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            playPcm(buffer)
        }
    }

    fun playCrash() {
        executor.execute {
            val durationMs = 380
            val numSamples = sampleRate * durationMs / 1000
            val buffer = ShortArray(numSamples)
            var phase = 0.0
            for (i in 0 until numSamples) {
                val p = i.toDouble() / numSamples
                val freq = (135.0 - p * 105.0).coerceAtLeast(28.0)
                phase += 2.0 * PI * freq / sampleRate
                val decay = 1.0 - p
                val noise = (Math.random() * 2.0 - 1.0) * 0.35
                val sample = (sin(phase) * 0.65 + noise) * decay * 0.55
                buffer[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            playPcm(buffer)
        }
    }

    fun release() {
        executor.shutdownNow()
    }

    private fun playPcm(buffer: ShortArray) {
        try {
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(buffer, 0, buffer.size)
            audioTrack.play()
            Thread.sleep((buffer.size.toDouble() / sampleRate * 1000).toLong() + 30)
            audioTrack.release()
        } catch (_: Exception) {}
    }
}
