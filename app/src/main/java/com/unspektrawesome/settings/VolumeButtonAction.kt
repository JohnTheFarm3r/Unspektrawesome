package com.unspektrawesome.settings

enum class VolumeButtonAction(val label: String, val detail: String, val direction: Int = 0) {
    SHUTTER("Shutter", "Take one photo per press. Holding the button does not take more photos."),
    SYSTEM_VOLUME("System volume", "Use this button for normal volume control."),
    DEVICE_EV_UP("Device EV +", "Brighten by one lens EV step. Requires camera Auto exposure.", 1),
    DEVICE_EV_DOWN("Device EV -", "Darken by one lens EV step. Requires camera Auto exposure.", -1),
    SPEKTRA_AE_NEXT("spektrafilm AE next", "Cycle spektrafilm exposure: Off, Center weighted, Median, then Off.", 1),
    SPEKTRA_AE_PREVIOUS("spektrafilm AE previous", "Cycle spektrafilm exposure backward: Off, Median, Center weighted, then Off.", -1),
}
