package org.yangtse.hearwrite

import android.app.Application
import org.yangtse.hearwrite.data.BuiltinLibraryRepository

/**
 * Application-scoped singleton container (manual DI per AGENTS.md — no framework).
 * Built-in library access is lazy: asset scanning never runs on the startup path.
 */
class HearWriteApplication : Application() {
    val libraryRepository: BuiltinLibraryRepository by lazy { BuiltinLibraryRepository(assets) }
}
