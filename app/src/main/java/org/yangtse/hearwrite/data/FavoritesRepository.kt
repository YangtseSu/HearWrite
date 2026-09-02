package org.yangtse.hearwrite.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Favorites: stable entry ids (`default_<category>_<label>` for built-in
 * lists, generated ids for history rows), mirroring `alice/src/lib/storage.ts`.
 * Only the id is stored — library content lives in assets, history rows in
 * Room — so both sources stay in sync automatically.
 */
class FavoritesRepository(private val dao: FavoritesDao) {

    fun observeIds(): Flow<Set<String>> =
        dao.observeIds().map { it.toSet() }.distinctUntilChanged()

    /** Toggle the favorite state of [id]; returns the new state. */
    suspend fun toggle(id: String): Boolean {
        return if (dao.exists(id)) {
            dao.delete(id)
            false
        } else {
            dao.insert(FavoriteEntity(id))
            true
        }
    }
}
