package com.tegeltech.werewolf.presentation

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tegeltech.werewolf.R

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TwoPhaseImageGallery()
        }
    }
}

@Composable
private fun TwoPhaseImageGallery() {
    var phase by rememberSaveable { mutableIntStateOf(1) }
    var virtualIndex by rememberSaveable { mutableIntStateOf(0) }
    var rotaryRemainder by remember { mutableFloatStateOf(0f) }
    var lastImageReachedAt by remember { mutableLongStateOf(0L) }

    if (phase == 3) {
        ChaseGameScreen(
            onCaught = {
                phase = 1
                virtualIndex = 0
                rotaryRemainder = 0f
            }
        )
        return
    }

    val view = LocalView.current
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val images = if (phase == 1) phaseOneImages else phaseTwoImages
    val imageIndex = virtualIndex.bounce(images.size)
    val isLastImage = imageIndex == images.lastIndex
    var howlPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(phase, isLastImage) {
        if (isLastImage) {
            lastImageReachedAt = System.currentTimeMillis()
            shakeOffset.snapTo(0f)
            for (target in SHAKE_OFFSETS_PX) {
                shakeOffset.animateTo(target, tween(SHAKE_STEP_DURATION_MS))
            }
        }
        if (phase == 2 && isLastImage) {
            howlPlayer = playHowl(context) { howlPlayer = null }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { translationX = shakeOffset.value }
            .onRotaryScrollEvent { event ->
                // Read virtualIndex fresh rather than trusting the composition-scoped
                // `isLastImage`: a fast spin can fire this callback several times per
                // frame, faster than recomposition updates that value or
                // `lastImageReachedAt`, which would let the pause get bypassed as stale.
                val onLastImageNow = virtualIndex.bounce(images.size) == images.lastIndex
                val pausedOnLastImage = onLastImageNow &&
                    System.currentTimeMillis() - lastImageReachedAt < ROTARY_PAUSE_AFTER_LAST_IMAGE_MS
                if (pausedOnLastImage) {
                    return@onRotaryScrollEvent true
                }

                rotaryRemainder += event.verticalScrollPixels

                // Increase this number if the crown feels too sensitive.
                // Reverse +steps to -steps below if your device feels backwards.
                val steps = (rotaryRemainder / ROTARY_PIXELS_PER_IMAGE).toInt()

                if (steps != 0) {
                    // A fast spin can produce a multi-image `steps` value that would
                    // otherwise leap straight over the last-image position without ever
                    // landing on it, silently skipping the pause. Clamp to land on it.
                    val period = 2 * (images.size - 1)
                    val clampedSteps = stepsUntilLastImage(virtualIndex, steps, period, images.lastIndex)
                    if (clampedSteps != null) {
                        virtualIndex += clampedSteps
                        rotaryRemainder = 0f
                    } else {
                        virtualIndex += steps
                        rotaryRemainder -= steps * ROTARY_PIXELS_PER_IMAGE
                    }
                    if (virtualIndex.bounce(images.size) == images.lastIndex) {
                        lastImageReachedAt = System.currentTimeMillis()
                    }
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            .clickable(
                enabled = isLastImage,
                onClick = {
                    if (phase == 1) {
                        phase = 2
                        virtualIndex = 0
                        rotaryRemainder = 0f
                    } else {
                        phase = 3
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = images[imageIndex]),
            contentDescription = "Phase $phase image ${imageIndex + 1} of ${images.size}",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // Optional tiny debug/status text. Delete this BasicText block for a pure image-only screen.
        BasicText(
            text = "${imageIndex + 1}",
            modifier = Modifier.align(Alignment.BottomCenter),
            style = TextStyle(
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

private const val ROTARY_PIXELS_PER_IMAGE = 72f
private const val ROTARY_PAUSE_AFTER_LAST_IMAGE_MS = 1000L

private const val SHAKE_STEP_DURATION_MS = 50
private val SHAKE_OFFSETS_PX = floatArrayOf(20f, -20f, 14f, -14f, 8f, -8f, 0f)

// Plays through the watch's built-in speaker even if a Bluetooth audio device is connected.
private fun playHowl(context: Context, onCompletion: () -> Unit): MediaPlayer? {
    val mediaPlayer = MediaPlayer.create(context, R.raw.howl) ?: return null
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val builtInSpeaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    if (builtInSpeaker != null) {
        mediaPlayer.preferredDevice = builtInSpeaker
    }
    mediaPlayer.setOnCompletionListener {
        it.release()
        onCompletion()
    }
    mediaPlayer.start()
    return mediaPlayer
}

// If moving `steps` from `virtualIndex` would jump past the virtual position that
// bounces to the last image, returns the (same-signed) step count that lands on it
// exactly instead. Returns null when no clamping is needed.
private fun stepsUntilLastImage(virtualIndex: Int, steps: Int, period: Int, lastImageIndex: Int): Int? {
    val dir = if (steps > 0) 1 else -1
    val distance = if (dir > 0) {
        (((lastImageIndex - virtualIndex) % period) + period) % period
    } else {
        (((virtualIndex - lastImageIndex) % period) + period) % period
    }
    val boundaryDistance = if (distance == 0) period else distance
    return if (boundaryDistance <= kotlin.math.abs(steps)) dir * boundaryDistance else null
}

// Reflects an ever-growing/shrinking virtual index into [0, size) so that
// hitting either end reverses direction instead of wrapping around.
private fun Int.bounce(size: Int): Int {
    if (size <= 1) return 0
    val period = 2 * (size - 1)
    val m = ((this % period) + period) % period
    return if (m < size) m else period - m
}

@DrawableRes
private val phaseOneImages = intArrayOf(
    R.drawable.moon0,
    R.drawable.moon1,
    R.drawable.moon2,
    R.drawable.moon3,
    R.drawable.moon4,
    R.drawable.moon5,
    R.drawable.moon6,
    R.drawable.moon7,
    R.drawable.moon8,
    R.drawable.moon9,
)

@DrawableRes
private val phaseTwoImages = intArrayOf(
    R.drawable.werewolf0,
    R.drawable.werewolf1,
    R.drawable.werewolf2,
    R.drawable.werewolf3,
    R.drawable.werewolf4,
    R.drawable.werewolf5,
    R.drawable.werewolf6,
    R.drawable.werewolf7,
    R.drawable.werewolf8,
    R.drawable.werewolf9,
)
