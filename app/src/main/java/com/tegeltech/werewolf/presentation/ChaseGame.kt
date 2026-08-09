package com.tegeltech.werewolf.presentation

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.annotation.DrawableRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.tegeltech.werewolf.R
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

private enum class ChaseEntityType { OBSTACLE, BOOST }

private class ChaseEntity(
    var x: Float,
    val lane: Float,
    val type: ChaseEntityType,
    var consumed: Boolean = false,
)

@Composable
fun ChaseGameScreen(onCaught: () -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val werewolfImageSizePx = remember { with(density) { WEREWOLF_IMAGE_SIZE_DP.toPx() } }
    val chickenImageSizePx = remember { with(density) { CHICKEN_IMAGE_SIZE_DP.toPx() } }
    val caughtImageSizePx = remember { with(density) { CAUGHT_IMAGE_SIZE_DP.toPx() } }
    val backgroundBitmap = ImageBitmap.imageResource(id = R.drawable.chase_background)
    val trapBitmap = ImageBitmap.imageResource(id = R.drawable.trap)

    var screenSize by remember { mutableStateOf(IntSize.Zero) }
    var backgroundScrollPx by remember { mutableFloatStateOf(0f) }
    var laneY by remember { mutableFloatStateOf(0.5f) }
    var rotaryRemainder by remember { mutableFloatStateOf(0f) }
    var distanceRemaining by remember { mutableFloatStateOf(INITIAL_DISTANCE) }
    var elapsedMs by remember { mutableFloatStateOf(0f) }
    var boostUntilMs by remember { mutableFloatStateOf(0f) }
    var slowUntilMs by remember { mutableFloatStateOf(0f) }
    var nextSpawnAtMs by remember { mutableFloatStateOf(SPAWN_INTERVAL_MIN_MS.toFloat()) }
    var flashColor by remember { mutableStateOf<Color?>(null) }
    var flashUntilMs by remember { mutableFloatStateOf(0f) }
    var hitBlinkStartMs by remember { mutableFloatStateOf(0f) }
    var hitBlinkUntilMs by remember { mutableFloatStateOf(0f) }
    var caught by remember { mutableStateOf(false) }
    var caughtElapsedMs by remember { mutableFloatStateOf(0f) }
    val obstacles = remember { mutableStateListOf<ChaseEntity>() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(caught) {
        if (caught) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            var lastFrameNanos = 0L
            val totalDurationMs = CAUGHT_FRAME_DURATION_MS * caughtImages.size
            while (caughtElapsedMs < totalDurationMs) {
                withFrameNanos { frameNanos ->
                    if (lastFrameNanos == 0L) lastFrameNanos = frameNanos
                    val dtMs = ((frameNanos - lastFrameNanos) / 1_000_000f).coerceAtMost(MAX_FRAME_DT_MS)
                    lastFrameNanos = frameNanos
                    caughtElapsedMs += dtMs
                }
            }
            delay(CATCH_HOLD_MS.milliseconds)
            onCaught()
        }
    }

    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        val werewolfXPx = { screenSize.width * WEREWOLF_X_FRACTION }
        while (!caught) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos == 0L) lastFrameNanos = frameNanos
                val dtMs = ((frameNanos - lastFrameNanos) / 1_000_000f).coerceAtMost(MAX_FRAME_DT_MS)
                lastFrameNanos = frameNanos

                if (screenSize.width == 0) return@withFrameNanos
                elapsedMs += dtMs

                val speedMultiplier = when {
                    elapsedMs < boostUntilMs -> BOOST_MULTIPLIER
                    elapsedMs < slowUntilMs -> SLOW_MULTIPLIER
                    else -> 1f
                }
                val speed = BASE_SPEED_PX_PER_MS * speedMultiplier

                val backgroundScaledWidth = backgroundBitmap.width *
                    (screenSize.height / backgroundBitmap.height.toFloat())
                backgroundScrollPx = (backgroundScrollPx + speed * dtMs * BACKGROUND_PARALLAX_FACTOR)
                    .mod(backgroundScaledWidth)

                val iterator = obstacles.iterator()
                while (iterator.hasNext()) {
                    val entity = iterator.next()
                    entity.x -= speed * dtMs
                    if (entity.x < -ENTITY_RADIUS_PX) {
                        iterator.remove()
                        continue
                    }
                    if (!entity.consumed &&
                        abs(entity.x - werewolfXPx()) < COLLISION_X_TOLERANCE_PX &&
                        abs(entity.lane - laneY) < COLLISION_LANE_TOLERANCE
                    ) {
                        entity.consumed = true
                        when (entity.type) {
                            ChaseEntityType.OBSTACLE -> {
                                slowUntilMs = elapsedMs + SLOW_DURATION_MS
                                boostUntilMs = elapsedMs
                                flashColor = Color.Red
                                hitBlinkStartMs = elapsedMs
                                hitBlinkUntilMs = elapsedMs + HIT_BLINK_DURATION_MS
                            }
                            ChaseEntityType.BOOST -> {
                                boostUntilMs = elapsedMs + BOOST_DURATION_MS
                                slowUntilMs = elapsedMs
                                flashColor = Color.Cyan
                            }
                        }
                        flashUntilMs = elapsedMs + FLASH_DURATION_MS
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }

                if (elapsedMs >= nextSpawnAtMs) {
                    val type = if (Random.nextFloat() < BOOST_SPAWN_CHANCE) {
                        ChaseEntityType.BOOST
                    } else {
                        ChaseEntityType.OBSTACLE
                    }
                    obstacles.add(
                        ChaseEntity(
                            x = screenSize.width.toFloat(),
                            lane = Random.nextFloat(),
                            type = type,
                        )
                    )
                    nextSpawnAtMs = elapsedMs + Random.nextInt(SPAWN_INTERVAL_MIN_MS, SPAWN_INTERVAL_MAX_MS)
                }

                distanceRemaining = (distanceRemaining - (speed - CHICKEN_SPEED_PX_PER_MS) * dtMs)
                    .coerceIn(0f, INITIAL_DISTANCE)
                if (distanceRemaining <= 0f && abs(laneY - CHICKEN_Y_FRACTION) < CATCH_LANE_TOLERANCE) {
                    caught = true
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { screenSize = it }
            .onRotaryScrollEvent { event ->
                rotaryRemainder += event.verticalScrollPixels
                val delta = rotaryRemainder / LANE_ROTARY_PIXELS_PER_FULL_RANGE
                if (delta != 0f) {
                    laneY = (laneY + delta).coerceIn(0f, 1f)
                    rotaryRemainder = 0f
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        val werewolfXPx = screenSize.width * WEREWOLF_X_FRACTION
        val werewolfYPx = screenSize.height * laneY

        val progress = 1f - (distanceRemaining / INITIAL_DISTANCE).coerceIn(0f, 1f)
        val chickenXPx = lerp(screenSize.width * CHICKEN_START_X_FRACTION, werewolfXPx + CATCH_DISTANCE_PX, progress)
        val chickenYPx = screenSize.height * CHICKEN_Y_FRACTION
        val chickenFrameIndex = (elapsedMs / CHICKEN_FRAME_DURATION_MS).toInt() % chickenImages.size
        val wolfFrameIndex = (elapsedMs / WOLF_FRAME_DURATION_MS).toInt() % wolfImages.size
        val caughtFrameIndex = (caughtElapsedMs / CAUGHT_FRAME_DURATION_MS).toInt()
            .coerceIn(0, caughtImages.size - 1)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val scaledWidth = backgroundBitmap.width * (size.height / backgroundBitmap.height.toFloat())
            val scaledSize = IntSize(scaledWidth.roundToInt(), size.height.roundToInt())
            var tileX = -backgroundScrollPx
            while (tileX < size.width) {
                drawImage(
                    image = backgroundBitmap,
                    dstOffset = IntOffset(tileX.roundToInt(), 0),
                    dstSize = scaledSize,
                )
                tileX += scaledWidth
            }

            obstacles.forEach { entity ->
                val y = entity.lane * size.height
                if (entity.type == ChaseEntityType.OBSTACLE) {
                    val trapVisualRadius = ENTITY_RADIUS_PX * TRAP_VISUAL_SCALE
                    val entitySize = (trapVisualRadius * 2f).roundToInt()
                    val trapCenter = Offset(entity.x, y)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                TRAP_GLOW_COLOR.copy(alpha = 0.4f),
                                TRAP_GLOW_COLOR.copy(alpha = 0f),
                            ),
                            center = trapCenter,
                            radius = TRAP_GLOW_RADIUS_PX,
                        ),
                        radius = TRAP_GLOW_RADIUS_PX,
                        center = trapCenter,
                    )
                    drawImage(
                        image = trapBitmap,
                        dstOffset = IntOffset(
                            (entity.x - trapVisualRadius).roundToInt(),
                            (y - trapVisualRadius).roundToInt(),
                        ),
                        dstSize = IntSize(entitySize, entitySize),
                    )
                } else if (!entity.consumed) {
                    val center = Offset(entity.x, y)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MOON_GLOW_COLOR.copy(alpha = 0.85f),
                                MOON_GLOW_COLOR.copy(alpha = 0f),
                            ),
                            center = center,
                            radius = MOON_GLOW_RADIUS_PX,
                        ),
                        radius = MOON_GLOW_RADIUS_PX,
                        center = center,
                    )
                    drawCircle(color = MOON_COLOR, radius = ENTITY_RADIUS_PX, center = center)
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.22f),
                        radius = ENTITY_RADIUS_PX * 0.82f,
                        center = center + Offset(ENTITY_RADIUS_PX * 0.32f, -ENTITY_RADIUS_PX * 0.12f),
                    )
                }
            }

            val currentFlashColor = flashColor
            if (currentFlashColor != null && elapsedMs < flashUntilMs) {
                val alpha = ((flashUntilMs - elapsedMs) / FLASH_DURATION_MS).coerceIn(0f, 1f) * FLASH_MAX_ALPHA
                drawRect(color = currentFlashColor.copy(alpha = alpha))
            }
        }

        if (!caught) {
            Image(
                painter = painterResource(id = chickenImages[chickenFrameIndex]),
                contentDescription = "Chicken running",
                modifier = Modifier
                    .size(CHICKEN_IMAGE_SIZE_DP)
                    .offset {
                        IntOffset(
                            (chickenXPx - chickenImageSizePx / 2f).roundToInt(),
                            (chickenYPx - chickenImageSizePx / 2f).roundToInt(),
                        )
                    },
                contentScale = ContentScale.Fit,
            )

            val werewolfVisible = if (elapsedMs < hitBlinkUntilMs) {
                (((elapsedMs - hitBlinkStartMs) / HIT_BLINK_INTERVAL_MS).toInt() % 2) == 0
            } else {
                true
            }
            if (werewolfVisible) {
                Image(
                    painter = painterResource(id = wolfImages[wolfFrameIndex]),
                    contentDescription = "Werewolf chasing the chicken",
                    modifier = Modifier
                        .size(WEREWOLF_IMAGE_SIZE_DP)
                        .offset {
                            IntOffset(
                                (werewolfXPx - werewolfImageSizePx / 2f).roundToInt(),
                                (werewolfYPx - werewolfImageSizePx / 2f).roundToInt(),
                            )
                        },
                    contentScale = ContentScale.Fit,
                )
            }
        } else {
            Image(
                painter = painterResource(id = caughtImages[caughtFrameIndex]),
                contentDescription = "Chicken caught",
                modifier = Modifier
                    .size(CAUGHT_IMAGE_SIZE_DP)
                    .offset {
                        IntOffset(
                            (werewolfXPx - caughtImageSizePx / 2f).roundToInt(),
                            (werewolfYPx - caughtImageSizePx / 2f).roundToInt(),
                        )
                    },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private const val INITIAL_DISTANCE = 1800f
private const val BASE_SPEED_PX_PER_MS = 0.28f
private const val CHICKEN_SPEED_PX_PER_MS = 0.22f
private const val BOOST_MULTIPLIER = 1.8f
private const val SLOW_MULTIPLIER = 0.35f
private const val BOOST_DURATION_MS = 900f
private const val SLOW_DURATION_MS = 900f
private const val FLASH_DURATION_MS = 350f
private const val FLASH_MAX_ALPHA = 0.35f
private const val MAX_FRAME_DT_MS = 48f
private const val SPAWN_INTERVAL_MIN_MS = 550
private const val SPAWN_INTERVAL_MAX_MS = 1100
private const val BOOST_SPAWN_CHANCE = 0.35f
private const val ENTITY_RADIUS_PX = 16f
private val MOON_COLOR = Color(0xFFF5F5F0)
private val MOON_GLOW_COLOR = Color(0xFF29B6F6)
private const val MOON_GLOW_RADIUS_PX = ENTITY_RADIUS_PX * 2.4f
private val TRAP_GLOW_COLOR = Color(0xFFFF1744)
private const val TRAP_GLOW_RADIUS_PX = ENTITY_RADIUS_PX * 1.8f
private const val TRAP_VISUAL_SCALE = 1.6f
private const val HIT_BLINK_DURATION_MS = 480f
private const val HIT_BLINK_INTERVAL_MS = 80f
private const val COLLISION_X_TOLERANCE_PX = 28f
private const val COLLISION_LANE_TOLERANCE = 0.12f
private const val CATCH_LANE_TOLERANCE = 0.15f
private const val WEREWOLF_X_FRACTION = 0.22f
private const val CHICKEN_START_X_FRACTION = 0.88f
private const val CHICKEN_Y_FRACTION = 0.5f
private const val CATCH_DISTANCE_PX = 34f
private const val LANE_ROTARY_PIXELS_PER_FULL_RANGE = 480f
private const val CATCH_HOLD_MS = 500L
private val WEREWOLF_IMAGE_SIZE_DP = 44.dp
private const val BACKGROUND_PARALLAX_FACTOR = 1f

private const val CHICKEN_FRAME_DURATION_MS = 90f
private val CHICKEN_IMAGE_SIZE_DP = 40.dp

@DrawableRes
private val chickenImages = intArrayOf(
    R.drawable.chicken1,
    R.drawable.chicken2,
    R.drawable.chicken3,
    R.drawable.chicken4,
    R.drawable.chicken5,
    R.drawable.chicken6,
)

private const val WOLF_FRAME_DURATION_MS = 90f

@DrawableRes
private val wolfImages = intArrayOf(
    R.drawable.wolf1,
    R.drawable.wolf2,
    R.drawable.wolf3,
    R.drawable.wolf4,
    R.drawable.wolf5,
    R.drawable.wolf6,
)

private const val CAUGHT_FRAME_DURATION_MS = 90f
private val CAUGHT_IMAGE_SIZE_DP = 96.dp

@DrawableRes
private val caughtImages = intArrayOf(
    R.drawable.caught1,
    R.drawable.caught2,
    R.drawable.caught3,
    R.drawable.caught4,
    R.drawable.caught5,
    R.drawable.caught6,
    R.drawable.caught7,
    R.drawable.caught8,
    R.drawable.caught9,
    R.drawable.caught10,
    R.drawable.caught11,
    R.drawable.caught12,
)
