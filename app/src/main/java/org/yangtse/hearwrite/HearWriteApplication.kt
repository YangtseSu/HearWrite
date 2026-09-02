package org.yangtse.hearwrite

import android.app.Application

/**
 * Application-scoped singleton container (manual DI per AGENTS.md — no framework).
 * Later phases add lazily initialized singletons as properties here.
 */
class HearWriteApplication : Application()
