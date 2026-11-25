package com.brucelyu.rocketheaddash

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Generates vertically scrolling obstacle waves with a mathematically guaranteed
 * reachable safe corridor (>= 1.92x rocket width) so unwinnable traps never occur.
 */
class FairWaveGenerator(
    private val random: Random = Random.Default
) {
    enum class ObstacleKind {
        ASTEROID,
        SATELLITE,
        WARNING_METEOR
    }

    enum class CollectibleKind {
        STAR,
        FUEL
    }

    data class SpawnedObstacle(
        var x: Float,
        var y: Float,
        val radius: Float,
        val kind: ObstacleKind,
        val patrolMinX: Float,
        val patrolMaxX: Float,
        var vx: Float,
        val speedScale: Float = 1f,
        var warningTimer: Float = 0f,
        var rotation: Float = 0f,
        var rotSpeed: Float = 0f,
        var grazed: Boolean = false,
        var destroyed: Boolean = false
    )

    data class SpawnedCollectible(
        var x: Float,
        var y: Float,
        val radius: Float,
        val kind: CollectibleKind,
        var collected: Boolean = false,
        var pulseOffset: Float = 0f
    )

    data class WaveResult(
        val corridorCenterX: Float,
        val corridorWidth: Float,
        val obstacles: List<SpawnedObstacle>,
        val collectibles: List<SpawnedCollectible>
    )

    var corridorCenterNorm: Float = 0.5f
        private set
    private var waveCounter = 0

    fun reset() {
        corridorCenterNorm = 0.5f
        waveCounter = 0
    }

    fun generateWave(
        screenWidth: Float,
        rocketWidth: Float,
        spawnY: Float,
        altitudeMeters: Float,
        faceMode: Boolean
    ): WaveResult {
        waveCounter++
        val diffScale = if (faceMode) 3600f else 2600f
        val difficulty = (altitudeMeters / diffScale).coerceIn(0f, 1f)

        // Guaranteed corridor width >= 1.92x rocketWidth (starts 2.65x, narrows smoothly to ~1.98x)
        val widthMult = (2.65f - difficulty * 0.67f).coerceAtLeast(1.92f)
        val corridorWidth = (rocketWidth * widthMult).coerceAtLeast(rocketWidth * 1.92f)
        val halfCorridor = corridorWidth * 0.5f

        val marginNorm = ((halfCorridor + 12f) / screenWidth).coerceIn(0.14f, 0.32f)
        val minCenterNorm = marginNorm
        val maxCenterNorm = 1f - marginNorm

        // Keep horizontal jump between consecutive waves comfortable for gentle head turns
        val maxShiftNorm = if (faceMode) 0.26f else 0.33f
        val low = max(minCenterNorm, corridorCenterNorm - maxShiftNorm)
        val high = min(maxCenterNorm, corridorCenterNorm + maxShiftNorm)
        val nextCenterNorm = if (high <= low) {
            0.5f
        } else {
            val rawTarget = low + random.nextFloat() * (high - low)
            // Gentle nudge away from repeating identical straight lines every wave
            if (abs(rawTarget - corridorCenterNorm) < 0.06f && waveCounter > 1) {
                val nudge = if (corridorCenterNorm > 0.5f) -0.14f else 0.14f
                (corridorCenterNorm + nudge).coerceIn(low, high)
            } else {
                rawTarget
            }
        }
        corridorCenterNorm = nextCenterNorm

        val corridorCenterX = screenWidth * corridorCenterNorm
        val safeLeft = corridorCenterX - halfCorridor
        val safeRight = corridorCenterX + halfCorridor

        val obstacles = ArrayList<SpawnedObstacle>()
        val collectibles = ArrayList<SpawnedCollectible>()

        val edgePad = 14f
        val leftZoneW = safeLeft - edgePad
        val rightZoneW = (screenWidth - edgePad) - safeRight

        val baseObsRadius = (rocketWidth * (0.40f + random.nextFloat() * 0.18f)).coerceIn(22f, 56f)

        // Populate Left side strictly outside [safeLeft, safeRight]
        spawnZoneObstacles(
            zoneLeft = edgePad,
            zoneRight = safeLeft,
            spawnY = spawnY,
            baseRadius = baseObsRadius,
            rocketWidth = rocketWidth,
            difficulty = difficulty,
            faceMode = faceMode,
            outObstacles = obstacles
        )

        // Populate Right side strictly outside [safeLeft, safeRight]
        spawnZoneObstacles(
            zoneLeft = safeRight,
            zoneRight = screenWidth - edgePad,
            spawnY = spawnY,
            baseRadius = baseObsRadius,
            rocketWidth = rocketWidth,
            difficulty = difficulty,
            faceMode = faceMode,
            outObstacles = obstacles
        )

        // Above 320m, occasionally trigger a red-line warning meteor strictly outside the safe corridor
        val canWarningMeteor = altitudeMeters >= 320f && waveCounter % 3 == 0
        if (canWarningMeteor) {
            val meteorR = (rocketWidth * 0.34f).coerceIn(18f, 36f)
            val useLeft = leftZoneW >= rightZoneW
            val zoneMin = if (useLeft) edgePad + meteorR else safeRight + meteorR + 4f
            val zoneMax = if (useLeft) safeLeft - meteorR - 4f else screenWidth - edgePad - meteorR
            if (zoneMax > zoneMin) {
                val mx = zoneMin + random.nextFloat() * (zoneMax - zoneMin)
                obstacles.add(
                    SpawnedObstacle(
                        x = mx,
                        y = -meteorR * 2f,
                        radius = meteorR,
                        kind = ObstacleKind.WARNING_METEOR,
                        patrolMinX = mx,
                        patrolMaxX = mx,
                        vx = 0f,
                        speedScale = 2.25f,
                        warningTimer = if (faceMode) 1.25f else 1.05f,
                        rotation = 0f,
                        rotSpeed = 0f
                    )
                )
            }
        }

        // Spawn collectible stars biased slightly toward corridor edges to encourage active head steering
        val starRadius = (rocketWidth * 0.22f).coerceIn(14f, 24f)
        val starBias = (if (random.nextBoolean()) 1f else -1f) * (halfCorridor * 0.42f)
        val starBaseX = (corridorCenterX + starBias).coerceIn(safeLeft + starRadius, safeRight - starRadius)
        val starCount = 2 + random.nextInt(3)
        for (i in 0 until starCount) {
            val arcX = (starBaseX + (i - 1) * starBias * 0.2f).coerceIn(safeLeft + starRadius, safeRight - starRadius)
            collectibles.add(
                SpawnedCollectible(
                    x = arcX,
                    y = spawnY - i * (rocketWidth * 0.65f),
                    radius = starRadius,
                    kind = CollectibleKind.STAR,
                    pulseOffset = i * 0.6f
                )
            )
        }

        // Periodic Plasma Fuel Cell inside corridor opposite side
        if (waveCounter % 4 == 0) {
            val fuelRadius = (rocketWidth * 0.26f).coerceIn(16f, 28f)
            val fuelX = (corridorCenterX - starBias * 0.85f).coerceIn(safeLeft + fuelRadius, safeRight - fuelRadius)
            collectibles.add(
                SpawnedCollectible(
                    x = fuelX,
                    y = spawnY - rocketWidth * 0.9f,
                    radius = fuelRadius,
                    kind = CollectibleKind.FUEL,
                    pulseOffset = 1.2f
                )
            )
        }

        return WaveResult(
            corridorCenterX = corridorCenterX,
            corridorWidth = corridorWidth,
            obstacles = obstacles,
            collectibles = collectibles
        )
    }

    private fun spawnZoneObstacles(
        zoneLeft: Float,
        zoneRight: Float,
        spawnY: Float,
        baseRadius: Float,
        rocketWidth: Float,
        difficulty: Float,
        faceMode: Boolean,
        outObstacles: MutableList<SpawnedObstacle>
    ) {
        val zoneWidth = zoneRight - zoneLeft
        if (zoneWidth < baseRadius * 2.15f) return

        // Decide whether this zone gets a static asteroid or moving satellite (or 2 asteroids in wide zones)
        val spawnSatellite = difficulty > 0.12f && zoneWidth >= baseRadius * 3.4f && random.nextFloat() < (0.28f + difficulty * 0.22f)
        if (spawnSatellite) {
            val r = (rocketWidth * 0.36f).coerceIn(20f, 40f)
            val minX = zoneLeft + r + 2f
            val maxX = zoneRight - r - 2f
            if (maxX > minX + 12f) {
                val startX = minX + random.nextFloat() * (maxX - minX)
                val speed = (if (faceMode) 75f else 105f) * (0.7f + difficulty * 0.6f)
                outObstacles.add(
                    SpawnedObstacle(
                        x = startX,
                        y = spawnY + (random.nextFloat() - 0.5f) * rocketWidth * 0.5f,
                        radius = r,
                        kind = ObstacleKind.SATELLITE,
                        patrolMinX = minX,
                        patrolMaxX = maxX,
                        vx = if (random.nextBoolean()) speed else -speed,
                        rotation = random.nextFloat() * 360f,
                        rotSpeed = (random.nextFloat() - 0.5f) * 60f
                    )
                )
            }
            return
        }

        // Static asteroid(s) placed cleanly inside [zoneLeft, zoneRight]
        val maxFit = (zoneWidth / (baseRadius * 2.4f)).toInt().coerceIn(1, 2)
        val count = if (maxFit == 2 && random.nextFloat() < (0.45f + difficulty * 0.35f)) 2 else 1
        val step = zoneWidth / count
        for (i in 0 until count) {
            val subLeft = zoneLeft + i * step
            val subRight = subLeft + step
            val r = (baseRadius * (0.85f + random.nextFloat() * 0.25f)).coerceAtMost((subRight - subLeft) * 0.44f)
            if (r < 16f) continue
            val minCenter = subLeft + r + 2f
            val maxCenter = subRight - r - 2f
            if (maxCenter < minCenter) continue
            val cx = minCenter + random.nextFloat() * (maxCenter - minCenter)
            val cy = spawnY + (random.nextFloat() - 0.5f) * rocketWidth * 0.7f
            outObstacles.add(
                SpawnedObstacle(
                    x = cx,
                    y = cy,
                    radius = r,
                    kind = ObstacleKind.ASTEROID,
                    patrolMinX = cx,
                    patrolMaxX = cx,
                    vx = 0f,
                    rotation = random.nextFloat() * 360f,
                    rotSpeed = (random.nextFloat() - 0.5f) * 85f
                )
            )
        }
    }
}
