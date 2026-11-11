package com.unspektrawesome.gallery

internal enum class ProcessingPriority(val label: String, val cpuPriority: Int, val gpuPauseMillis: Int, val detail: String) {
    LOW("Low", 19, 6, "Leaves more time for other apps. Exports take longer."),
    NORMAL("Normal", 10, 2, "Balances export speed with background responsiveness."),
    HIGH("High", 0, 0, "Processes as quickly as available resources allow."),
    ;

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: NORMAL
    }
}
