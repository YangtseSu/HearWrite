package org.yangtse.hearwrite.data

import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.yangtse.hearwrite.domain.CompoundTables
import org.yangtse.hearwrite.domain.parseCompoundTables

/**
 * Loader for the 组词 candidate tables shipped at `assets/compounds/compounds.json`
 * (390 KB, `{compounds, learned}` pools — see [parseCompoundTables]).
 *
 * Per AGENTS.md the file is parsed lazily on the first lookup on
 * [Dispatchers.IO] and kept in a process-lifetime memory singleton — never on
 * the startup path. A load failure degrades to [CompoundTables.EMPTY] (single
 * chars without a meaning column simply get no 组词 pass) instead of blocking
 * dictation.
 */
class CompoundRepository(private val assets: AssetManager) {

    private val mutex = Mutex()
    private var cached: CompoundTables? = null

    suspend fun tables(): CompoundTables = mutex.withLock {
        cached ?: run {
            val loaded = try {
                withContext(Dispatchers.IO) {
                    val json = assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
                    parseCompoundTables(json)
                }
            } catch (e: Exception) {
                // Defensive asset boundary: a broken/missing asset must not
                // block dictation — it degrades to bare-char phrasing.
                Log.e(TAG, "compounds.json load failed, dictation degrades to bare chars", e)
                CompoundTables.EMPTY
            }
            cached = loaded
            loaded
        }
    }

    companion object {
        private const val ASSET_PATH = "compounds/compounds.json"
        private const val TAG = "CompoundRepository"
    }
}
