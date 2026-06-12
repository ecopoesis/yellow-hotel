package gbc.app

import arrow.core.Option
import arrow.core.getOrElse
import gbc.core.api.AudioSink
import gbc.core.api.InputSource
import gbc.core.api.Ports
import gbc.core.apu.takeSamples
import gbc.core.cart.MbcState
import gbc.core.cart.batteryRam
import gbc.core.cart.loadBatteryRam
import gbc.core.cart.parseCartridge
import gbc.core.system.SystemState
import gbc.core.system.postBootState
import gbc.core.system.stepFrame
import java.io.File
import java.util.concurrent.atomic.AtomicReference

private const val FRAME_NANOS = 16_742_706L // 59.7275 Hz

/**
 * Drives the core on a dedicated thread. Pacing: the blocking audio write is
 * the clock; with no audio device we fall back to nanoTime pacing. Video is
 * latest-frame-wins through an AtomicReference so the UI never blocks us.
 */
class Emulator(
    private val romFile: File,
    private val audio: Option<AudioSink>,
) {
    val frame = AtomicReference(IntArray(160 * 144))

    @Volatile
    var buttons: Int = 0

    @Volatile
    private var running = true

    private val saveFile = File(romFile.parentFile, romFile.nameWithoutExtension + ".sav")
    private val sampleBuf = FloatArray(8192)

    fun stop() {
        running = false
    }

    fun run(maxFrames: Long = Long.MAX_VALUE) {
        val cart = parseCartridge(romFile.readBytes())
            .getOrElse { error("could not load ${romFile.name}: $it") }
        var lastSaveHash = 0
        if (saveFile.exists()) {
            val image = saveFile.readBytes()
            loadBatteryRam(cart, image) // wrong-size image: Left, cart RAM stays fresh
            lastSaveHash = image.contentHashCode()
        }
        var s = postBootState(cart)
        val ports = Ports(input = InputSource { buttons })

        var frames = 0L
        var lastFlush = System.nanoTime()
        var ramWasEnabled = false
        var nextFrame = System.nanoTime()

        while (running && frames < maxFrames) {
            s = stepFrame(s, ports)
            frames++
            frame.set(s.ppu.frame.copyOf())

            val (apu, count) = takeSamples(s.apu, sampleBuf, sampleBuf.size / 2)
            s = s.copy(apu = apu)
            audio.fold(
                {
                    // no audio device: pace against the wall clock
                    nextFrame += FRAME_NANOS
                    val wait = nextFrame - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) else nextFrame = System.nanoTime()
                },
                { it.push(sampleBuf, count) }, // blocking write paces emulation
            )

            val ramEnabled = (s.cart.mbc as? MbcState.Mbc5)?.ramEnabled ?: false
            val now = System.nanoTime()
            if ((ramWasEnabled && !ramEnabled) || now - lastFlush > 2_000_000_000L) {
                lastSaveHash = flushSave(s, lastSaveHash)
                lastFlush = now
            }
            ramWasEnabled = ramEnabled
        }
        flushSave(s, lastSaveHash)
    }

    private fun flushSave(s: SystemState, lastHash: Int): Int =
        batteryRam(s.cart).fold(
            { lastHash },
            { image ->
                val hash = image.contentHashCode()
                if (hash != lastHash) saveFile.writeBytes(image)
                hash
            },
        )
}
