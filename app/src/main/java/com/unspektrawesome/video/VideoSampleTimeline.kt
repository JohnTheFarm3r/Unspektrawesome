package com.unspektrawesome.video

/** Shares the first encoded image's clock origin across video, audio and metadata. */
internal class VideoSampleTimeline {
    var originUs: Long? = null
        private set

    fun video(inputTimeUs: Long) {
        if (originUs == null) originUs = inputTimeUs
    }

    fun presentationTimeUs(inputTimeUs: Long): Long? = originUs?.let { origin ->
        (inputTimeUs - origin).takeIf { it >= 0 }
    }
}
