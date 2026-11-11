package com.unspektrawesome.preview

internal class RawFrameDecimator {
    data class Sample(val group: Long, val index: Int, val count: Int) {
        val output: Boolean get() = index == count - 1
    }
    private var interval = 1
    private var count = 1
    private var position = 0
    private var group = 0L
    private var previousNs = Long.MIN_VALUE

    fun sample(timestampNs: Long, frameInterval: Int, averageFrames: Int = 1): Sample? {
        require(frameInterval in 1..120)
        require(averageFrames in 1..frameInterval)
        if (interval != frameInterval || count != averageFrames || timestampNs < previousNs) {
            interval = frameInterval
            count = averageFrames
            position = 0
        }
        if (timestampNs == previousNs) return null
        previousNs = timestampNs
        if (position == 0) group = timestampNs
        val sample = if (position < count) Sample(group, position, count) else null
        position = (position + 1) % interval
        return sample
    }
}
