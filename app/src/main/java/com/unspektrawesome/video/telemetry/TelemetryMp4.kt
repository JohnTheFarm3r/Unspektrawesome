package com.unspektrawesome.video.telemetry

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Adds timed tracks without moving or decoding existing audio and video chunks. */
internal object TelemetryMp4 {
    data class Sample(val ptsUs: Long, val protobuf: ByteArray, val extension: ByteArray)
    private data class Atom(val offset: Long, val size: Long, val header: Int, val type: String)
    private const val MAX_FRAMES = 1_000_000
    private const val MAX_INDEX = 128 * 1024 * 1024

    data class Timeline(val timescale: Int, val presentationTicks: LongArray) {
        fun microseconds(ticks: Long): Long = ticks / timescale * 1_000_000 +
            ((ticks % timescale) * 1_000_000 + timescale / 2) / timescale
    }

    fun videoTimeline(input: FileChannel): Timeline {
        fun children(atom: Atom) = atoms(input, atom.offset + atom.header, atom.offset + atom.size)
        fun payload(atom: Atom) = ByteBuffer.wrap(read(input, atom.offset + atom.header, (atom.size - atom.header).toInt()))
        val movie = atoms(input, 0, input.size()).single { it.type == "moov" }
        val video = children(movie).filter { it.type == "trak" }.single { track ->
            val media = children(track).single { it.type == "mdia" }
            val handler = payload(children(media).single { it.type == "hdlr" })
            handler.getInt(8) == 0x76696465
        }
        val media = children(video).single { it.type == "mdia" }
        val header = payload(children(media).single { it.type == "mdhd" })
        val version = header.get(0).toInt()
        require(version in 0..1)
        val scale = header.getInt(if (version == 0) 12 else 20)
        require(scale > 0)
        val table = children(children(media).single { it.type == "minf" }).single { it.type == "stbl" }
        val tables = children(table)
        val size = payload(tables.single { it.type == "stsz" })
        val count = size.getInt(8)
        require(count in 1..MAX_FRAMES)
        fun runs(type: String, optional: Boolean = false): LongArray {
            val atom = tables.singleOrNull { it.type == type }
            if (optional && atom == null) return LongArray(count)
            val data = payload(checkNotNull(atom))
            val flags = data.int
            require(flags == 0 || type == "ctts" && flags == 0x01000000)
            val entries = data.int
            require(entries in 1..count && data.remaining().toLong() == entries * 8L)
            val values = LongArray(count)
            var index = 0
            repeat(entries) {
                val run = data.int
                val value = data.int.let { if (flags == 0x01000000) it.toLong() else it.toLong() and 0xffffffffL }
                require(run in 1..count - index)
                repeat(run) { values[index++] = value }
            }
            require(index == count)
            return values
        }
        val durations = runs("stts")
        val presentation = runs("ctts", true)
        var decodeTime = 0L
        for (i in presentation.indices) {
            presentation[i] += decodeTime
            decodeTime = Math.addExact(decodeTime, durations[i])
        }
        children(video).singleOrNull { it.type == "edts" }?.let { edits ->
            val data = payload(children(edits).single { it.type == "elst" })
            val flags = data.int
            require(flags == 0 || flags == 0x01000000)
            require(data.int == 1) { "Multiple video edits require an explicit capture mapping" }
            if (flags == 0) data.int else data.long
            val mediaStart = if (flags == 0) data.int.toLong() else data.long
            require(mediaStart >= 0 && data.int == 0x10000 && !data.hasRemaining())
            for (i in presentation.indices) presentation[i] -= mediaStart
        }
        require(presentation.all { it >= 0 })
        return Timeline(scale, presentation)
    }

    fun append(input: FileChannel, output: FileChannel, samples: Sequence<Sample>, count: Int, durationUs: Long) {
        require(count in 1..MAX_FRAMES && durationUs > 0)
        val originalSize = input.size()
        val movie = atoms(input, 0, originalSize).single { it.type == "moov" }
        require(movie.size <= MAX_INDEX)
        val children = atoms(input, movie.offset + movie.header, movie.offset + movie.size)
        val mvhd = children.single { it.type == "mvhd" }
        val movieHeader = read(input, mvhd.offset, mvhd.size.toInt())
        val version = movieHeader[mvhd.header].toInt()
        require(version == 0 || version == 1)
        val timescale = ByteBuffer.wrap(movieHeader).getInt(mvhd.header + if (version == 0) 12 else 20)
        require(timescale > 0)
        val nextId = children.filter { it.type == "trak" }.maxOf { track ->
            val tkhd = atoms(input, track.offset + track.header, track.offset + track.size).single { it.type == "tkhd" }
            val data = ByteBuffer.wrap(read(input, tkhd.offset + tkhd.header, 32))
            data.getInt(if (data.get(0).toInt() == 0) 12 else 20)
        } + 1
        require(nextId in 1..Int.MAX_VALUE - 2)
        val pts = LongArray(count)
        val offsets = Array(2) { LongArray(count) }
        val sizes = Array(2) { IntArray(count) }
        var position = originalSize + 16
        try {
            write(output, originalSize, bytes { writeInt(1); writeBytes("mdat"); writeLong(16) })
            val buffer = ByteBuffer.allocate(64 * 1024)
            var writtenPosition = position
            fun flush() {
                buffer.flip()
                while (buffer.hasRemaining()) {
                    val written = output.write(buffer, writtenPosition)
                    check(written > 0)
                    writtenPosition += written
                }
                buffer.clear()
            }
            var index = 0
            for (sample in samples) {
                require(index < count && sample.ptsUs >= 0 && (index == 0 || sample.ptsUs > pts[index - 1]))
                pts[index] = sample.ptsUs
                for ((track, payload) in listOf(sample.protobuf, sample.extension).withIndex()) {
                    require(payload.size in 1..8 * 1024 * 1024)
                    offsets[track][index] = position
                    sizes[track][index] = payload.size
                    var copied = 0
                    while (copied < payload.size) {
                        val length = minOf(payload.size - copied, buffer.remaining())
                        buffer.put(payload, copied, length)
                        copied += length
                        if (!buffer.hasRemaining()) flush()
                    }
                    position += payload.size
                }
                index++
            }
            require(index == count && durationUs > pts.last())
            flush()
            check(writtenPosition == position)
            write(output, originalSize + 8, bytes { writeLong(position - originalSize) })
            ByteBuffer.wrap(movieHeader).putInt(movieHeader.size - 4, nextId + 2)
            val updated = atom("moov", bytes {
                for (child in children) write(if (child.type == "mvhd") movieHeader else read(input, child.offset, child.size.toInt()))
                for (track in 0..1) write(track(nextId + track, if (track == 0) "gyrf" else "ustb", pts,
                    sizes[track], offsets[track], durationUs, timescale))
            })
            write(output, position, updated)
            output.force(true)
            write(output, movie.offset + 4, "free".toByteArray(Charsets.US_ASCII))
            output.force(true)
        } catch (failure: Throwable) {
            runCatching {
                write(output, movie.offset + 4, "moov".toByteArray(Charsets.US_ASCII))
                output.truncate(originalSize); output.force(true)
            }
            throw failure
        }
    }

    private fun track(id: Int, type: String, pts: LongArray, sizes: IntArray, offsets: LongArray,
        durationUs: Long, movieScale: Int): ByteArray {
        val tkhd = atom("tkhd", bytes {
            writeInt(0x01000003); writeLong(0); writeLong(0); writeInt(id); writeInt(0)
            writeLong(durationUs * movieScale / 1_000_000); writeLong(0)
            writeShort(0); writeShort(0); writeShort(0); writeShort(0)
            listOf(65536, 0, 0, 0, 65536, 0, 0, 0, 0x40000000).forEach { writeInt(it) }
            writeInt(0); writeInt(0)
        })
        val mdhd = atom("mdhd", bytes {
            writeInt(0x01000000); writeLong(0); writeLong(0); writeInt(1_000_000); writeLong(durationUs)
            writeShort(0x55c4); writeShort(0)
        })
        val hdlr = atom("hdlr", bytes {
            writeInt(0); writeInt(0); writeBytes("meta"); write(ByteArray(12))
            writeBytes(if (type == "gyrf") "Gyroflow" else "Unspektra Stabilization v1"); writeByte(0)
        })
        val stsd = atom("stsd", bytes { writeInt(0); writeInt(1); write(atom(type, ByteArray(6) + byteArrayOf(0, 1))) })
        val stts = atom("stts", bytes {
            writeInt(0); writeInt(pts.size)
            for (i in pts.indices) {
                val delta = (if (i + 1 < pts.size) pts[i + 1] else durationUs) - pts[i]
                require(delta in 1..0xffffffffL)
                writeInt(1); writeInt(delta.toInt())
            }
        })
        require(pts[0] <= Int.MAX_VALUE)
        val ctts = if (pts[0] == 0L) ByteArray(0) else atom("ctts", bytes {
            writeInt(0); writeInt(1); writeInt(pts.size); writeInt(pts[0].toInt())
        })
        val stsc = atom("stsc", bytes { writeInt(0); writeInt(1); writeInt(1); writeInt(1); writeInt(1) })
        val stsz = atom("stsz", bytes { writeInt(0); writeInt(0); writeInt(sizes.size); sizes.forEach { writeInt(it) } })
        val co64 = atom("co64", bytes { writeInt(0); writeInt(offsets.size); offsets.forEach { writeLong(it) } })
        val stbl = atom("stbl", stsd + stts + ctts + stsc + stsz + co64)
        val dinf = atom("dinf", atom("dref", bytes { writeInt(0); writeInt(1); write(atom("url ", bytes { writeInt(1) })) }))
        return atom("trak", tkhd + atom("mdia", mdhd + hdlr + atom("minf", atom("nmhd", ByteArray(4)) + dinf + stbl)))
    }

    private fun atoms(input: FileChannel, start: Long, end: Long): List<Atom> = buildList {
        var offset = start
        while (offset < end) {
            require(end - offset >= 8 && size < 10000)
            val data = ByteBuffer.wrap(read(input, offset, 8))
            val size = data.int.toLong() and 0xffffffffL
            val type = ByteArray(4).also { data.get(it) }.toString(Charsets.ISO_8859_1)
            val header = if (size == 1L) 16 else 8
            val length = when (size) { 0L -> end - offset; 1L -> ByteBuffer.wrap(read(input, offset + 8, 8)).long; else -> size }
            require(length >= header && length <= end - offset)
            add(Atom(offset, length, header, type)); offset += length
        }
    }
    private fun read(input: FileChannel, position: Long, count: Int): ByteArray {
        require(count in 0..MAX_INDEX)
        val data = ByteBuffer.allocate(count)
        while (data.hasRemaining()) check(input.read(data, position + data.position()) > 0)
        return data.array()
    }
    private fun write(output: FileChannel, position: Long, data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        while (buffer.hasRemaining()) check(output.write(buffer, position + buffer.position()) > 0)
    }
    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(block)
    }.toByteArray()
    private fun atom(type: String, data: ByteArray): ByteArray = bytes {
        writeInt(data.size + 8); writeBytes(type); write(data)
    }
}
