package org.yangtse.hearwrite.data

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

/**
 * UI sound effects (AGENTS.md Phase 7): a soft watch tick on the final
 * countdown second and a chime when a dictation round completes — the
 * in-house `audio/tick.wav` / `audio/chime.wav` assets shipped with alice
 * (volumes 0.5 / 0.6). Playback is fire-and-forget and fully defensive: any
 * failure degrades to silence and [enabled] gates both sounds (提示音
 * setting). Every call allocates its own player and releases it on
 * completion/error so an effect can never leak or wedge playback.
 */
class SoundEffects(private val context: Context) {

    /** Set from the persisted 提示音 setting (default on). */
    @Volatile
    var enabled: Boolean = true

    /** Final second of the word countdown. */
    fun playTick() {
        play("audio/tick.wav", TICK_VOLUME)
    }

    /** A dictation round finished. */
    fun playChime() {
        play("audio/chime.wav", CHIME_VOLUME)
    }

    private fun play(asset: String, volume: Float) {
        if (!enabled) return
        val player = MediaPlayer()
        try {
            val afd = context.assets.openFd(asset)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            player.setVolume(volume, volume)
            player.setOnPreparedListener { mp ->
                try {
                    mp.start()
                } catch (e: Exception) {
                    Log.w(TAG, "start failed for $asset", e)
                }
            }
            player.setOnCompletionListener { mp ->
                try {
                    mp.release()
                } catch (e: Exception) {
                    // Already released; nothing to do.
                }
            }
            player.setOnErrorListener { mp, _, _ ->
                try {
                    mp.release()
                } catch (e: Exception) {
                    // Already released.
                }
                true
            }
            player.prepareAsync()
            Log.i(TAG, "playing $asset (vol $volume)")
        } catch (e: Exception) {
            Log.w(TAG, "play failed for $asset", e)
            try {
                player.release()
            } catch (e2: Exception) {
                // Nothing left to release.
            }
        }
    }

    companion object {
        private const val TAG = "SoundEffects"
        private const val TICK_VOLUME = 0.5f
        private const val CHIME_VOLUME = 0.6f
    }
}
