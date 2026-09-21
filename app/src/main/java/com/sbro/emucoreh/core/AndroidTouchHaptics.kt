package com.sbro.emucoreh.core

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.pow
import kotlin.math.roundToInt

object AndroidTouchHaptics {
    enum class ButtonPhase {
        PRESS,
        RELEASE
    }

    private val hapticAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    @Suppress("DEPRECATION")
    fun play(
        context: Context,
        view: View? = null,
        strengthPercent: Int,
        durationMs: Long
    ) {
        val amplitude = ((strengthPercent.coerceIn(10, 100) / 100f) * 255f)
            .roundToInt()
            .coerceIn(1, 255)
        val duration = durationMs.coerceIn(80L, 260L)
        val effect = createTouchEffect(amplitude, duration)
        val vibrator = findVibrator(context)
        val played = vibrator != null && runCatching { vibrate(vibrator, effect) }.isSuccess
        if (!played) {
            performViewHaptic(view, ButtonPhase.PRESS)
        }
    }

    /** Short, distinct virtual-button feedback. Press is crisp; release is lighter and shorter. */
    fun playButton(
        context: Context,
        view: View? = null,
        strengthPercent: Int,
        preset: Int,
        phase: ButtonPhase
    ) {
        val vibrator = findVibrator(context)
        val played = vibrator != null && runCatching {
            vibrate(vibrator, createButtonEffect(vibrator, strengthPercent, preset, phase))
        }.isSuccess
        if (!played) {
            performViewHaptic(view, phase)
        }
    }

    @Suppress("DEPRECATION")
    private fun performViewHaptic(view: View?, phase: ButtonPhase) {
        view ?: return
        val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (phase == ButtonPhase.PRESS) {
                HapticFeedbackConstants.GESTURE_START
            } else {
                HapticFeedbackConstants.GESTURE_END
            }
        } else {
            if (phase == ButtonPhase.PRESS) {
                HapticFeedbackConstants.VIRTUAL_KEY
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
        }
        runCatching { view.performHapticFeedback(feedback, flags) }
    }

    private fun createButtonEffect(
        vibrator: Vibrator,
        strengthPercent: Int,
        preset: Int,
        phase: ButtonPhase
    ): VibrationEffect {
        data class Pulse(
            val durationMs: Long,
            val minimumAmplitude: Int,
            val defaultAmplitude: Int,
            val maximumAmplitude: Int
        )

        val profile = when (preset.coerceIn(0, 3)) {
            0 -> if (phase == ButtonPhase.PRESS) Pulse(18L, 20, 89, 148) else Pulse(9L, 10, 46, 77)
            2 -> if (phase == ButtonPhase.PRESS) Pulse(13L, 38, 165, 255) else Pulse(7L, 18, 104, 173)
            3 -> if (phase == ButtonPhase.PRESS) Pulse(32L, 55, 191, 255) else Pulse(20L, 28, 125, 209)
            else -> if (phase == ButtonPhase.PRESS) Pulse(24L, 28, 153, 255) else Pulse(14L, 14, 98, 163)
        }
        if (!runCatching { vibrator.hasAmplitudeControl() }.getOrDefault(false)) {
            if (strengthPercent.coerceIn(10, 100) == 60) {
                return VibrationEffect.createPredefined(
                    when {
                        preset == 0 -> VibrationEffect.EFFECT_TICK
                        preset == 3 && phase == ButtonPhase.PRESS -> VibrationEffect.EFFECT_HEAVY_CLICK
                        phase == ButtonPhase.PRESS -> VibrationEffect.EFFECT_CLICK
                        else -> VibrationEffect.EFFECT_TICK
                    }
                )
            }
            // Fixed-amplitude motors cannot vary force. Duration still gives the full slider
            // a clearly perceptible weak-to-strong response instead of ignoring the setting.
            val durationFactor = if (strengthPercent <= 60) {
                0.55f + ((strengthPercent.coerceIn(10, 60) - 10) / 50f) * 0.45f
            } else {
                1f + ((strengthPercent.coerceIn(60, 100) - 60) / 40f) * 0.25f
            }
            val duration = (profile.durationMs * durationFactor)
                .roundToInt()
                .coerceAtLeast(6)
                .toLong()
            return VibrationEffect.createOneShot(
                duration,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
        }

        val strength = strengthPercent.coerceIn(10, 100)
        val amplitude = if (strength <= 60) {
            val lowerProgress = ((strength - 10) / 50f).pow(0.82f)
            profile.minimumAmplitude +
                (profile.defaultAmplitude - profile.minimumAmplitude) * lowerProgress
        } else {
            val upperProgress = ((strength - 60) / 40f).pow(0.82f)
            profile.defaultAmplitude +
                (profile.maximumAmplitude - profile.defaultAmplitude) * upperProgress
        }.roundToInt()
            .coerceIn(profile.minimumAmplitude, profile.maximumAmplitude)
        return VibrationEffect.createOneShot(profile.durationMs, amplitude)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(vibrator: Vibrator, effect: VibrationEffect) {
        // The user explicitly enabled in-app haptics. USAGE_TOUCH can be silently muted by
        // the device-wide touch-feedback switch, so retain the working game channel here.
        vibrator.vibrate(effect, hapticAudioAttributes)
    }

    private fun createTouchEffect(amplitude: Int, durationMs: Long): VibrationEffect {
        if (durationMs <= 120L) {
            return VibrationEffect.createPredefined(
                if (amplitude >= 180) {
                    VibrationEffect.EFFECT_HEAVY_CLICK
                } else {
                    VibrationEffect.EFFECT_CLICK
                }
            )
        }

        val firstPulse = (durationMs * 0.62f).roundToInt().coerceIn(50, 170).toLong()
        val secondPulse = (durationMs * 0.30f).roundToInt().coerceIn(28, 90).toLong()
        val secondAmplitude = (amplitude * 0.72f).roundToInt().coerceIn(1, 255)
        return VibrationEffect.createWaveform(
            longArrayOf(0L, firstPulse, 22L, secondPulse),
            intArrayOf(0, amplitude, 0, secondAmplitude),
            -1
        )
    }

    private fun findVibrator(context: Context): Vibrator? {
        val appContext = context.applicationContext
        val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(VibratorManager::class.java)
            buildList {
                manager?.defaultVibrator?.let(::add)
                manager?.vibratorIds?.forEach { id ->
                    runCatching { manager.getVibrator(id) }.getOrNull()?.let(::add)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        }
        return candidates
            .distinctBy { System.identityHashCode(it) }
            .firstOrNull { runCatching { it.hasVibrator() }.getOrDefault(false) }
    }
}
