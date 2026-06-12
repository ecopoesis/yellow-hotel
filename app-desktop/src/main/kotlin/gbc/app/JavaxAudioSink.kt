package gbc.app

import arrow.core.None
import arrow.core.Option
import arrow.core.some
import gbc.core.api.AudioSink
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * 48 kHz 16-bit stereo line with a ~50 ms buffer. The blocking write is the
 * emulator's pacing clock: the line drains in real time, so pushing samples
 * faster than real time blocks until there is room.
 */
class JavaxAudioSink private constructor(private val line: SourceDataLine) : AudioSink {

    private val bytes = ByteArray(16384)

    override fun push(stereo: FloatArray, frames: Int) {
        var bi = 0
        for (i in 0 until frames * 2) {
            val v = (stereo[i].coerceIn(-1f, 1f) * 32767).toInt()
            bytes[bi++] = v.toByte()
            bytes[bi++] = (v shr 8).toByte()
            if (bi == bytes.size) {
                line.write(bytes, 0, bi)
                bi = 0
            }
        }
        if (bi > 0) line.write(bytes, 0, bi)
    }

    companion object {
        /** None when the host has no usable audio device (headless CI, etc.). */
        fun open(): Option<JavaxAudioSink> = try {
            val format = AudioFormat(48_000f, 16, 2, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            line.open(format, 9600) // 50 ms * 48000 Hz * 4 bytes
            line.start()
            JavaxAudioSink(line).some()
        } catch (e: Exception) {
            System.err.println("audio unavailable (${e.message}); running silent")
            None
        }
    }
}
