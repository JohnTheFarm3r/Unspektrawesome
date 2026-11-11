package com.unspektrawesome.spektra

import android.content.SharedPreferences
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

interface SpektraStatePersistence {
    fun load(): SpektraState?
    fun save(state: SpektraState)
}

class SharedPreferencesSpektraStatePersistence(
    private val preferences: SharedPreferences,
    private val key: String = DEFAULT_KEY,
) : SpektraStatePersistence {
    override fun load(): SpektraState? = preferences.getString(key, null)?.let(SpektraStateJson::decode)

    override fun save(state: SpektraState) {
        preferences.edit().putString(key, SpektraStateJson.encode(state)).apply()
    }

    companion object {
        const val DEFAULT_KEY = "spektra_state_json"
    }
}

class AsyncSpektraStatePersistence(
    private val delegate: SpektraStatePersistence,
) : SpektraStatePersistence, AutoCloseable {
    private val lock = Any()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "UnspektrawesomeSpektraPersistence")
    }
    private var pending: SpektraState? = null
    private var draining = false
    private var closed = false

    override fun load(): SpektraState? = delegate.load()

    override fun save(state: SpektraState) {
        synchronized(lock) {
            if (closed) return
            pending = state
            if (draining) return
            draining = true
            try {
                executor.execute(::drain)
            } catch (_: RejectedExecutionException) {
                draining = false
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            executor.shutdown()
        }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                val value = pending
                pending = null
                if (value == null) draining = false
                value
            } ?: return
            delegate.save(next)
        }
    }
}

object InMemorySpektraStatePersistence : SpektraStatePersistence {
    override fun load(): SpektraState? = null
    override fun save(state: SpektraState) = Unit
}
