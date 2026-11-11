package com.unspektrawesome.vulkan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ShaderPreparationState(
    val total: Int,
    val completed: Int = 0,
    val checking: Boolean = true,
    val required: Boolean = false,
    val hidden: Boolean = false,
    val finished: Boolean = false,
    val failed: Boolean = false,
) {
    val showDialog: Boolean get() = !checking && required && !hidden && (!finished || failed)
    val blocksCamera: Boolean get() = checking || showDialog
}

internal class ShaderPreparationProgress(
    total: Int,
    private val readCompletedIdentity: () -> String?,
    private val saveCompletedIdentity: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(ShaderPreparationState(total))
    val state = mutableState.asStateFlow()
    private var identity: String? = null

    @Synchronized fun setTotal(total: Int) {
        require(total > 0)
        mutableState.value = mutableState.value.copy(total = total)
    }

    @Synchronized fun checkCache(identity: String, compatibleCacheLoaded: Boolean) {
        if (!mutableState.value.checking) return
        this.identity = identity
        mutableState.value = mutableState.value.copy(checking = false,
            required = !compatibleCacheLoaded || readCompletedIdentity() != identity)
    }

    @Synchronized fun stepCompleted() {
        val previous = mutableState.value
        if (previous.finished) return
        val completed = (previous.completed + 1).coerceAtMost(previous.total)
        val finished = completed == previous.total
        if (finished) identity?.let(saveCompletedIdentity)
        mutableState.value = previous.copy(completed = completed, finished = finished)
    }

    @Synchronized fun hide() {
        mutableState.value = mutableState.value.copy(hidden = true)
    }

    @Synchronized fun interrupt() {
        mutableState.value = mutableState.value.copy(checking = false, finished = true)
    }

    @Synchronized fun fail() {
        if (mutableState.value.finished) return
        mutableState.value = mutableState.value.copy(checking = false, required = true, finished = true, failed = true)
    }
}
