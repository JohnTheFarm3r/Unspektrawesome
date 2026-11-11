package com.unspektrawesome.gallery

internal const val GALLERY_GUIDE_REVISION = 20

internal data class GalleryGuideStep(val tab: Int, val target: String, val title: String, val description: String, val scrollItem: Int = 0)

internal val galleryGuideSteps = listOf(
    GalleryGuideStep(0, "tab0", "Your gallery",
        "Gallery brings together camera photos, developed JPEGs and videos. Hold the camera thumbnail for 0.5 seconds to open it. Items stay ordered by capture time.\n\nGallery can recover earlier photos and pending exports from saved DNGs after a reinstall. If media is missing, use Missing photos or videos? or Media access to allow all-files access. Recovered shots keep their saved look and framing."),
    GalleryGuideStep(0, "filters", "Browse and share",
        "Use the media dropdown to filter photos, videos and BURST. Group captures by day or month. Long-press an item to select it.\n\nOpen a photo to zoom or browse the filmstrip. Back from a video or burst returns to that strip. Share and Details are below; More contains RAW sharing and deletion. In BURST, tap a thumbnail, choose Full resolution to inspect it, then Export JPEG to save it. The burst stays in Gallery. Set the time before the shutter in Camera settings, General, BURST."),
    GalleryGuideStep(1, "batch", "Every shot keeps its look",
        "Deferred captures wait in Processing. Each card shows the saved framing, lens, output resolution and look. Export applies the shutter-time crop and lens color calibration before spektrafilm, including any custom DCP, and preserves the shot's location. Replacing or removing a DCP later cannot alter waiting photos; its calibration is saved in each export recipe."),
    GalleryGuideStep(1, "start", "Start a processing session",
        "Tap Start exporting to process waiting photos, or use a card's Export or Select controls for individual shots. Progress shows completed photos and the current stage. Pause finishes the current photo before stopping."),
    GalleryGuideStep(1, "queue-tabs", "Your queue is kept",
        "Exported shows finished photos. Needs attention holds failed exports for Retry; Cancelled holds shots you cancelled. Interrupted batches resume when Gallery reopens. Cancel one waiting export or all waiting exports without deleting any DNG.", 1),
    GalleryGuideStep(2, "capture-mode", "Choose now or later",
        "Later in Gallery turns deferred capture on. Process now turns it off. This is the same setting as Camera settings, General. It affects new shots; waiting photos keep their saved settings. The guide does not change your choice."),
    GalleryGuideStep(2, "priority", "Set the processing pace",
        "Low gives other work more room, Normal balances processing, and High favors throughput. Image quality stays identical. Changes apply to the next photo. Pause when hot lets the phone cool; returning to the camera also pauses after the current photo.", 1),
    GalleryGuideStep(2, "notifications", "Follow progress outside Gallery",
        "Allow optional notifications during onboarding to see the current photo, batch progress and a pause control. Turn the detailed card on or off here. If notifications are blocked, turning it on opens Android notification settings.", 2),
    GalleryGuideStep(2, "originals", "Originals stay yours",
        "DNGs keep the full sensor data and crop recipe. Delete RAWs after export is off by default and requires confirmation of permanent loss. It applies to exports started after enabling it. Only published JPEGs with the expected dimensions, complete decoding and plausible image content allow cleanup. Failed, cancelled, recovered or suspicious exports keep their RAW. Checks cannot catch every rendering bug; deleted RAWs cannot be recovered. Each exported card shows whether its original remains. Replay this tour below.", 3),
)
