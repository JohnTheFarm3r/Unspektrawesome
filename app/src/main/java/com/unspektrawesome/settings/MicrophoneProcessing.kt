package com.unspektrawesome.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** How the phone processes the microphone signal for video sound. Each choice is one of Android's microphone sources. */
enum class MicrophoneProcessing(val label: String, val detail: String, val source: Int) {
    CAMCORDER("Camcorder", "Tuned by the phone maker for video", MediaRecorder.AudioSource.CAMCORDER),
    STANDARD("Standard", "The phone's usual microphone processing", MediaRecorder.AudioSource.MIC),
    SYSTEM_DEFAULT("System default", "Android chooses the processing", MediaRecorder.AudioSource.DEFAULT),
    UNPROCESSED("Unprocessed", "The microphone signal without noise reduction or automatic level", MediaRecorder.AudioSource.UNPROCESSED),
    VOICE_RECOGNITION("Voice recognition", "Light processing tuned for speech", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    VOICE_PERFORMANCE("Voice performance", "Tuned for singing and live performance", MediaRecorder.AudioSource.VOICE_PERFORMANCE),
    VOICE_COMMUNICATION("Voice call", "Echo and noise suppression tuned for calls", MediaRecorder.AudioSource.VOICE_COMMUNICATION);

    companion object {
        const val SAMPLE_RATE = 48_000

        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: CAMCORDER

        /**
         * The choices this phone accepts for 48 kHz mono recording, or null without microphone access.
         * Android only reports whether a source opens; some phones apply the same processing to several of them.
         * Unprocessed is offered whenever it opens: custom system builds often leave the system's unprocessed
         * support flag off even though the phone's audio drivers provide an unprocessed recording path.
         */
        @SuppressLint("MissingPermission")
        fun available(context: Context): Set<MicrophoneProcessing>? {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return emptySet()
            return entries.filterTo(linkedSetOf()) { choice ->
                runCatching {
                    val record = AudioRecord.Builder().setAudioSource(choice.source)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(minimum * 2).build()
                    try { record.state == AudioRecord.STATE_INITIALIZED } finally { record.release() }
                }.getOrDefault(false)
            }
        }
    }
}
