package com.unspektrawesome.video

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Adds 16-bit little-endian PCM as a QuickTime `sowt` track to a finished movie and marks the
 * file as QuickTime. Existing chunks stay in place; the new index is appended like other metadata.
 */
internal object PcmAudioTrack {
    private data class Atom(val offset: Long, val size: Long, val header: Int, val type: String)
    private const val MAX_INDEX = 128 * 1024 * 1024
    private const val CHUNK_FRAMES = 24_000

    /**
     * Writes [padFrames] of silence followed by [frames] frames read from [pcm] starting at
     * [pcmOffsetBytes]. The PCM is interpreted as mono or interleaved [channels] at [sampleRate].
     */
    fun append(input: FileChannel, output: FileChannel, pcm: FileChannel, pcmOffsetBytes: Long,
        padFrames: Long, frames: Long, sampleRate: Int, channels: Int) {
        require(sampleRate in 1..65_535 && channels in 1..2)
        require(padFrames >= 0 && frames >= 0 && padFrames + frames > 0)
        val frameBytes = 2L * channels
        require(pcmOffsetBytes >= 0 && pcmOffsetBytes + frames * frameBytes <= pcm.size())
        val totalFrames = padFrames + frames
        val chunks = ((totalFrames + CHUNK_FRAMES - 1) / CHUNK_FRAMES).toInt()
        val originalSize = input.size()
        val topLevel = atoms(input, 0, originalSize)
        val fileType = topLevel.first()
        require(fileType.type == "ftyp" && fileType.size >= 16 && fileType.size <= 1024)
        val movie = topLevel.single { it.type == "moov" }
        require(movie.size <= MAX_INDEX)
        val children = atoms(input, movie.offset + movie.header, movie.offset + movie.size)
        val mvhd = children.single { it.type == "mvhd" }
        val movieHeader = read(input, mvhd.offset, mvhd.size.toInt())
        val version = movieHeader[mvhd.header].toInt()
        require(version == 0 || version == 1)
        val movieScale = ByteBuffer.wrap(movieHeader).getInt(mvhd.header + if (version == 0) 12 else 20)
        require(movieScale > 0)
        val trackId = children.filter { it.type == "trak" }.maxOf { track ->
            val tkhd = atoms(input, track.offset + track.header, track.offset + track.size).single { it.type == "tkhd" }
            val data = ByteBuffer.wrap(read(input, tkhd.offset + tkhd.header, 24))
            data.getInt(if (data.get(0).toInt() == 0) 12 else 20)
        } + 1
        require(trackId in 1..Int.MAX_VALUE - 1)
        val originalFileType = read(input, fileType.offset, fileType.size.toInt())
        val dataStart = originalSize + 16
        val dataEnd = dataStart + totalFrames * frameBytes
        try {
            write(output, originalSize, bytes { writeInt(1); writeBytes("mdat"); writeLong(dataEnd - originalSize) })
            var position = dataStart
            val silence = ByteBuffer.allocate(64 * 1024)
            var remaining = padFrames * frameBytes
            while (remaining > 0) {
                silence.clear().limit(minOf(remaining, silence.capacity().toLong()).toInt())
                while (silence.hasRemaining()) {
                    val written = output.write(silence, position)
                    check(written > 0) { "Unable to write audio" }
                    position += written; remaining -= written
                }
            }
            var copied = 0L
            val audioBytes = frames * frameBytes
            while (copied < audioBytes) {
                val moved = pcm.transferTo(pcmOffsetBytes + copied, audioBytes - copied, positioned(output, position))
                check(moved > 0) { "Unable to copy audio" }
                copied += moved; position += moved
            }
            check(position == dataEnd)
            ByteBuffer.wrap(movieHeader).putInt(movieHeader.size - 4, trackId + 1)
            val updated = atom("moov", bytes {
                for (child in children) write(if (child.type == "mvhd") movieHeader else read(input, child.offset, child.size.toInt()))
                write(track(trackId, totalFrames, chunks, dataStart, sampleRate, channels, movieScale))
            })
            write(output, dataEnd, updated)
            output.force(true)
            write(output, movie.offset + 4, "free".toByteArray(Charsets.US_ASCII))
            write(output, fileType.offset, quickTimeFileType(fileType.size.toInt()))
            output.force(true)
        } catch (failure: Throwable) {
            runCatching {
                write(output, fileType.offset, originalFileType)
                write(output, movie.offset + 4, "moov".toByteArray(Charsets.US_ASCII))
                output.truncate(originalSize); output.force(true)
            }
            throw failure
        }
    }

    /** Keeps the original `ftyp` size so no other box moves. */
    private fun quickTimeFileType(size: Int): ByteArray = ByteBuffer.allocate(size).apply {
        putInt(size); put("ftyp".toByteArray(Charsets.US_ASCII))
        put("qt  ".toByteArray(Charsets.US_ASCII)); putInt(0x200)
        while (remaining() >= 4) put("qt  ".toByteArray(Charsets.US_ASCII))
    }.array()

    private fun track(id: Int, frames: Long, chunks: Int, dataStart: Long, sampleRate: Int, channels: Int,
        movieScale: Int): ByteArray {
        val frameBytes = 2 * channels
        val durationMovie = (frames * movieScale + sampleRate / 2) / sampleRate
        val tkhd = atom("tkhd", bytes {
            writeInt(0x01000003); writeLong(0); writeLong(0); writeInt(id); writeInt(0)
            writeLong(durationMovie); writeLong(0)
            writeShort(0); writeShort(1); writeShort(0x0100); writeShort(0)
            listOf(65536, 0, 0, 0, 65536, 0, 0, 0, 0x40000000).forEach { writeInt(it) }
            writeInt(0); writeInt(0)
        })
        val mdhd = atom("mdhd", bytes {
            writeInt(0x01000000); writeLong(0); writeLong(0); writeInt(sampleRate); writeLong(frames)
            writeShort(0x55c4); writeShort(0)
        })
        val hdlr = atom("hdlr", bytes {
            writeInt(0); writeInt(0); writeBytes("soun"); write(ByteArray(12)); writeBytes("SoundHandler"); writeByte(0)
        })
        val sampleEntry = atom("sowt", bytes {
            write(ByteArray(6)); writeShort(1)
            writeShort(0); writeShort(0); writeInt(0)
            writeShort(channels); writeShort(16); writeShort(0); writeShort(0)
            writeInt(sampleRate shl 16)
        })
        val stsd = atom("stsd", bytes { writeInt(0); writeInt(1); write(sampleEntry) })
        require(frames <= 0xffffffffL)
        val stts = atom("stts", bytes { writeInt(0); writeInt(1); writeInt(frames.toInt()); writeInt(1) })
        val lastChunkFrames = (frames - (chunks - 1).toLong() * CHUNK_FRAMES).toInt()
        val stsc = atom("stsc", bytes {
            val entries = if (lastChunkFrames == CHUNK_FRAMES || chunks == 1) 1 else 2
            writeInt(0); writeInt(entries)
            writeInt(1); writeInt(if (chunks == 1) lastChunkFrames else CHUNK_FRAMES); writeInt(1)
            if (entries == 2) { writeInt(chunks); writeInt(lastChunkFrames); writeInt(1) }
        })
        val stsz = atom("stsz", bytes { writeInt(0); writeInt(frameBytes); writeInt(frames.toInt()) })
        val co64 = atom("co64", bytes {
            writeInt(0); writeInt(chunks)
            for (chunk in 0 until chunks) writeLong(dataStart + chunk.toLong() * CHUNK_FRAMES * frameBytes)
        })
        val stbl = atom("stbl", stsd + stts + stsc + stsz + co64)
        val dinf = atom("dinf", atom("dref", bytes { writeInt(0); writeInt(1); write(atom("url ", bytes { writeInt(1) })) }))
        val smhd = atom("smhd", ByteArray(8))
        return atom("trak", tkhd + atom("mdia", mdhd + hdlr + atom("minf", smhd + dinf + stbl)))
    }

    private fun positioned(output: FileChannel, position: Long) = object : java.nio.channels.WritableByteChannel {
        private var at = position
        override fun write(source: ByteBuffer): Int = output.write(source, at).also { at += it }
        override fun isOpen() = output.isOpen
        override fun close() = Unit
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
