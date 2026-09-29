// SPDX-FileCopyrightText: 2026 Yangtse Su
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yangtse.hearwrite.domain

/** App theme preference (DataStore `theme`): follow the system, or force one mode. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        /** Tolerant parse of the stored key (unknown/blank → [SYSTEM]). */
        fun fromStored(raw: String?): ThemeMode = entries.firstOrNull {
            it.name.equals(raw, ignoreCase = true)
        } ?: SYSTEM
    }
}
