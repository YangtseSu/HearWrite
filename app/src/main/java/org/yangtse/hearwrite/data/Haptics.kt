package org.yangtse.hearwrite.data

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Haptic feedback helpers (alice `lib/haptics.ts` parity). Only the patterns
 * this app uses are ported, with expo-haptics' exact Android waveforms so the
 * feel matches upstream. Every call is defensive: a missing vibrator or a
 * missing permission degrades to silence, never a crash.
 */
object Haptics {

    /**
     * Notification-style warning pulse — alice's feedback when marking a
     * word wrong (`notifyWarning()`; timings `0,40,120,60` ms at amplitudes
     * `0,40,0,60`, expo-haptics `HapticsNotificationType.warning`).
     */
    fun notifyWarning(context: Context) {
        try {
            val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 40, 120, 60),
                    intArrayOf(0, 40, 0, 60),
                    -1,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "warning vibration failed", e)
        }
    }

    private const val TAG = "Haptics"
}
