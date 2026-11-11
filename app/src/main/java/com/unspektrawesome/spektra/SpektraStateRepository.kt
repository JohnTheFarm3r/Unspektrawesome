package com.unspektrawesome.spektra

import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The single authoritative source of versioned SpektraFilm processing state. */
class SpektraStateRepository(
    private val persistence: SpektraStatePersistence,
    private val maxUndoDepth: Int = DEFAULT_UNDO_DEPTH,
    private val normalizeParams: (SpektraFilmParams) -> SpektraFilmParams = { it },
    val customProfiles: SpektraProfiles = SpektraProfiles(),
    val cardOrders: SpektraCardOrders = SpektraCardOrders(),
    private val lensCorrections: SpektraLensCorrectionHooks = SpektraLensCorrectionHooks.None,
) {
    private val lock = Any()
    private val undoHistory = ArrayDeque<SpektraState>()
    private val mutableState: MutableStateFlow<SpektraState>

    init {
        require(maxUndoDepth > 0) { "maxUndoDepth must be positive" }
        val loaded = persistence.load() ?: SpektraState.DEFAULT
        val params = normalizeParams(loaded.params)
        val initial = if (params == loaded.params) loaded else {
            check(loaded.revisionId < Long.MAX_VALUE) { "Spektra revision counter exhausted" }
            loaded.copy(revisionId = loaded.revisionId + 1, params = params).also(persistence::save)
        }
        mutableState = MutableStateFlow(initial)
    }

    val state: StateFlow<SpektraState> = mutableState.asStateFlow()
    private val mutableSelectedProfileId = MutableStateFlow(customProfiles.profiles.value.firstOrNull {
        normalizeParams(it.params) == mutableState.value.params
    }?.id)
    val selectedProfileId = mutableSelectedProfileId.asStateFlow()

    fun clearProfileSelection() { mutableSelectedProfileId.value = null }

    fun applyProfile(profile: SpektraProfile) {
        val params = profile.corrections?.let { lensCorrections.apply(it, profile.params) } ?: profile.params
        updateParams { params }
        mutableSelectedProfileId.value = profile.id
    }

    fun saveProfile(name: String, replaceId: String? = null): SpektraProfile {
        val params = state.value.params
        val profile = customProfiles.save(name, params, replaceId, lensCorrections.capture(params))
        mutableSelectedProfileId.value = profile.id
        return profile
    }

    fun deleteProfile(id: String) {
        customProfiles.delete(id)
        if (mutableSelectedProfileId.value == id) mutableSelectedProfileId.value = null
    }

    fun update(transform: (SpektraState) -> SpektraState): SpektraState = synchronized(lock) {
        val current = mutableState.value
        val transformed = transform(current)
        val candidate = transformed.copy(revisionId = current.revisionId, params = normalizeParams(transformed.params))
        commit(current, candidate, recordUndo = true)
    }

    fun updateParams(transform: (SpektraFilmParams) -> SpektraFilmParams): SpektraState =
        update { state -> state.copy(params = transform(state.params)) }

    /**
     * Loads the shadows and highlight compression of a newly selected lens. This is not an edit,
     * so it is not undoable, and earlier undo steps keep the new lens's values.
     */
    fun switchLensTone(tone: LensTone): SpektraState = synchronized(lock) {
        val current = mutableState.value
        val candidate = current.copy(params = normalizeParams(tone.applyTo(current.params)))
        val switched = commit(current, candidate, recordUndo = false)
        val history = undoHistory.map { it.copy(params = tone.applyTo(it.params)) }
        undoHistory.clear()
        undoHistory.addAll(history)
        switched
    }

    fun reset(): SpektraState = synchronized(lock) {
        val current = mutableState.value
        val candidate = SpektraState.DEFAULT.copy(revisionId = current.revisionId, params = normalizeParams(SpektraState.DEFAULT.params))
        commit(current, candidate, recordUndo = true)
    }

    fun undo(): Boolean = synchronized(lock) {
        if (undoHistory.isEmpty()) return@synchronized false
        val current = mutableState.value
        val previous = requireNotNull(undoHistory.peekLast()).copy(revisionId = current.revisionId)
        commit(current, previous, recordUndo = false)
        undoHistory.removeLast()
        true
    }

    fun canUndo(): Boolean = synchronized(lock) { undoHistory.isNotEmpty() }

    fun captureSnapshot(): SpektraCaptureSnapshot = synchronized(lock) {
        val current = mutableState.value
        SpektraCaptureSnapshot(current.revisionId, current)
    }

    private fun commit(
        current: SpektraState,
        candidate: SpektraState,
        recordUndo: Boolean,
    ): SpektraState {
        if (candidate.sameProcessingAs(current)) return current
        check(current.revisionId < Long.MAX_VALUE) { "Spektra revision counter exhausted" }

        val revised = candidate.copy(revisionId = current.revisionId + 1L)
        persistence.save(revised)

        if (recordUndo) {
            if (undoHistory.size == maxUndoDepth) undoHistory.removeFirst()
            undoHistory.addLast(current)
        }

        mutableState.value = revised
        return revised
    }

    companion object {
        const val DEFAULT_UNDO_DEPTH = 50
    }
}
