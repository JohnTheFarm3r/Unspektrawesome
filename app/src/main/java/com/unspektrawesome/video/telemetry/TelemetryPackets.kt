package com.unspektrawesome.video.telemetry

import com.google.protobuf.CodedOutputStream
import com.google.protobuf.WireFormat
import com.unspektrawesome.telemetry.proto.Gyroflow as G
import java.io.ByteArrayOutputStream

internal object TelemetryPackets {
    fun motion(timestampUs: Double, x: Float, y: Float, z: Float): ByteArray {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return ByteArray(0)
        return G.FrameMetadata.newBuilder().addImu(G.IMUData.newBuilder()
            .setSampleTimestampUs(timestampUs)
            .setGyroscopeX(Math.toDegrees(x.toDouble()).toFloat())
            .setGyroscopeY(Math.toDegrees(y.toDouble()).toFloat())
            .setGyroscopeZ(Math.toDegrees(z.toDouble()).toFloat())).build().toByteArray()
    }

    fun main(frame: ByteArray, number: Int, motion: ByteArray, header: G.Header? = null): ByteArray {
        require(number > 0)
        val bytes = ByteArrayOutputStream(frame.size + motion.size + (header?.serializedSize ?: 0) + 64)
        val output = CodedOutputStream.newInstance(bytes)
        output.writeString(G.Main.MAGIC_STRING_FIELD_NUMBER, "GyroflowProtobuf")
        output.writeUInt32(G.Main.PROTOCOL_VERSION_FIELD_NUMBER, 1)
        if (header != null) output.writeMessage(G.Main.HEADER_FIELD_NUMBER, header)
        output.writeTag(G.Main.FRAME_FIELD_NUMBER, WireFormat.WIRETYPE_LENGTH_DELIMITED)
        output.writeUInt32NoTag(frame.size + motion.size +
            CodedOutputStream.computeUInt32Size(G.FrameMetadata.FRAME_NUMBER_FIELD_NUMBER, number))
        // Protobuf fields can be joined without decoding the prepared frame and motion again.
        output.writeRawBytes(frame)
        output.writeUInt32(G.FrameMetadata.FRAME_NUMBER_FIELD_NUMBER, number)
        output.writeRawBytes(motion)
        output.flush()
        return bytes.toByteArray()
    }

    fun extension(frame: String, ptsUs: Long, containerTicks: Long, inputPtsUs: Long, motion: CharSequence,
        header: String? = null, diagnostics: String? = null, issues: String? = null): ByteArray {
        require(frame.endsWith('}'))
        return buildString(frame.length + motion.length + (header?.length ?: 0) + 256) {
            append("{\"version\":1,\"frame\":")
            append(frame, 0, frame.length - 1)
            append(",\"presentationUs\":").append(ptsUs)
            append(",\"containerPresentationTicks\":").append(containerTicks)
            append(",\"encoderInputPtsUs\":").append(inputPtsUs)
            append("},\"gyroRawBits\":[").append(motion).append(']')
            if (header != null) append(",\"header\":").append(header)
            if (diagnostics != null) append(",\"diagnostics\":").append(diagnostics)
            if (issues != null) append(",\"issues\":").append(issues)
            append('}')
        }.toByteArray(Charsets.UTF_8)
    }
}
