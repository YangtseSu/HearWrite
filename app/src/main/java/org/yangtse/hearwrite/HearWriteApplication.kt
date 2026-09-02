package org.yangtse.hearwrite

import android.app.Application
import org.yangtse.hearwrite.data.BuiltinLibraryRepository
import org.yangtse.hearwrite.data.DictationSessionStore
import org.yangtse.hearwrite.data.SettingsRepository
import org.yangtse.hearwrite.data.SystemSpeaker

/**
 * Application-scoped singleton container (manual DI per AGENTS.md — no
 * framework). Everything is lazy: asset scanning and TTS init never run on
 * the startup path.
 */
class HearWriteApplication : Application() {
    val libraryRepository: BuiltinLibraryRepository by lazy { BuiltinLibraryRepository(assets) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    /** System TTS engine; created lazily on the first utterance. */
    val systemSpeaker: SystemSpeaker by lazy { SystemSpeaker(this) }

    /** Word-list handoff for starting a dictation session. */
    val dictationSession: DictationSessionStore by lazy { DictationSessionStore() }
}
