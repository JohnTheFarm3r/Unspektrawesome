package com.unspektrawesome.camera.session;

public interface RawFrameConsumer {
    /** Maximum retained frames waiting for processing during recording. */
    int MAX_QUEUED_FRAMES = 3;

    default boolean preserveFrameOrder() { return false; }

    /** Called before accessing preview pixel buffers. */
    default boolean shouldProcessFrame(long sensorTimestampNs) { return true; }

    /** Import or retain the native buffer handle before this method returns. */
    void onRawFrame(RawHardwareFrame frame);

    /** Called before accessing ISP viewfinder buffers. */
    default boolean shouldProcessIspFrame(long sensorTimestampNs) { return false; }

    /** Import or retain the ISP buffer before this method returns. */
    default void onIspFrame(IspHardwareFrame frame) { }
}
