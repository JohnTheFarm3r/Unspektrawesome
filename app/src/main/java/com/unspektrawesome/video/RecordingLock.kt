package com.unspektrawesome.video

/** What a recording started by holding the shutter keeps fixed until it stops. */
data class RecordingLock(
    val shutter: Boolean = true,
    val iso: Boolean = true,
    val focus: Boolean = true,
    val whiteBalance: Boolean = true,
) {
    /** Shutter speed and ISO together are the whole exposure. */
    val exposure: Boolean get() = shutter && iso
    val any: Boolean get() = shutter || iso || focus || whiteBalance

    /**
     * What the camera in use can hold. Shutter speed and ISO lock on their own only with the app's own exposure
     * control. Android's automatic exposure holds both or neither, so either choice holds both there.
     */
    fun resolve(manualExposure: Boolean, separateExposure: Boolean): RecordingLock = when {
        !manualExposure -> copy(shutter = false, iso = false)
        separateExposure -> this
        else -> (shutter || iso).let { copy(shutter = it, iso = it) }
    }

    /** Short label under the recording timer, such as AE/WB or S/AF. */
    val label: String get() = listOfNotNull(
        when { exposure -> "AE"; shutter -> "S"; iso -> "ISO"; else -> null },
        "AF".takeIf { focus }, "WB".takeIf { whiteBalance }).joinToString("/")

    /** Tells screen readers what holding the shutter does. */
    val holdDescription: String get() = when {
        exposure -> when {
            focus && whiteBalance -> "Record with exposure, focus and white balance locked"
            focus -> "Record with exposure and focus locked"
            whiteBalance -> "Record with exposure and white balance locked"
            else -> "Record with exposure locked"
        }
        shutter -> when {
            focus && whiteBalance -> "Record with shutter speed, focus and white balance locked"
            focus -> "Record with shutter speed and focus locked"
            whiteBalance -> "Record with shutter speed and white balance locked"
            else -> "Record with shutter speed locked"
        }
        iso -> when {
            focus && whiteBalance -> "Record with ISO, focus and white balance locked"
            focus -> "Record with ISO and focus locked"
            whiteBalance -> "Record with ISO and white balance locked"
            else -> "Record with ISO locked"
        }
        focus && whiteBalance -> "Record with focus and white balance locked"
        focus -> "Record with focus locked"
        else -> "Record with white balance locked"
    }

    /** Tells screen readers about the running recording. {value1} is its duration. */
    val recordingDescription: String get() = when {
        exposure -> when {
            focus && whiteBalance -> "Recording {value1}, exposure, focus and white balance locked"
            focus -> "Recording {value1}, exposure and focus locked"
            whiteBalance -> "Recording {value1}, exposure and white balance locked"
            else -> "Recording {value1}, exposure locked"
        }
        shutter -> when {
            focus && whiteBalance -> "Recording {value1}, shutter speed, focus and white balance locked"
            focus -> "Recording {value1}, shutter speed and focus locked"
            whiteBalance -> "Recording {value1}, shutter speed and white balance locked"
            else -> "Recording {value1}, shutter speed locked"
        }
        iso -> when {
            focus && whiteBalance -> "Recording {value1}, ISO, focus and white balance locked"
            focus -> "Recording {value1}, ISO and focus locked"
            whiteBalance -> "Recording {value1}, ISO and white balance locked"
            else -> "Recording {value1}, ISO locked"
        }
        focus && whiteBalance -> "Recording {value1}, focus and white balance locked"
        focus -> "Recording {value1}, focus locked"
        whiteBalance -> "Recording {value1}, white balance locked"
        else -> "Recording {value1}"
    }

    companion object {
        val NONE = RecordingLock(shutter = false, iso = false, focus = false, whiteBalance = false)
    }
}
