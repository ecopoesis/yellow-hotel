package gbc.app

import arrow.core.getOrElse
import gbc.core.api.Button
import gbc.core.api.InputSource
import gbc.core.api.Ports
import gbc.core.apu.takeSamples
import gbc.core.cart.batteryRam
import gbc.core.cart.loadBatteryRam
import gbc.core.cart.parseCartridge
import gbc.core.ppu.SCREEN_H
import gbc.core.ppu.SCREEN_W
import gbc.core.system.SystemState
import gbc.core.system.peek
import gbc.core.system.postBootState
import gbc.core.system.stepFrame
import java.awt.image.BufferedImage
import java.io.File
import java.net.ServerSocket
import javax.imageio.ImageIO

/**
 * Headless play harness: drives the core at full speed, controlled over TCP.
 * One connection = one request line of ';'-separated commands, then a reply:
 *
 *   p <buttons> <frames>          hold buttons (string of U D L R A B T=start E=select, or '-') for N frames
 *   s <frames>                    step N frames with nothing held
 *   m <buttons> <count> <hold> <gap>   repeat: hold buttons <hold> frames, release <gap> frames
 *   shot <path>                   write the current frame as a 2x PNG
 *   pos                           report player map id + coords on the reply line
 *   peek <hexaddr> [len]          report <len> bytes from CPU address space on the reply
 *   save                          flush battery RAM to the .sav now
 *   quit                          flush and exit
 *
 * Replies "ok <total-frames>[ <extra>]" or "err <message>". The screen is the
 * primary output; `pos`/`peek` additionally expose read-only memory as a
 * navigation/diagnosis aid (side-effect-free, never mutates emulator state).
 */
private const val SCALE = 2

private class Harness(romFile: File) {
    private val saveFile = File(romFile.parentFile, romFile.nameWithoutExtension + ".sav")
    private val cart = parseCartridge(romFile.readBytes()).getOrElse { error("could not load ${romFile.name}: $it") }
    private var s: SystemState
    private var held = 0
    private var frames = 0L
    private var lastSaveHash = 0
    private val sampleBuf = FloatArray(8192)
    private var extra = StringBuilder()

    init {
        if (saveFile.exists()) {
            val image = saveFile.readBytes()
            loadBatteryRam(cart, image)
            lastSaveHash = image.contentHashCode()
        }
        s = postBootState(cart)
    }

    private val ports = Ports(input = InputSource { held })

    private fun step(n: Int) {
        repeat(n) {
            s = stepFrame(s, ports)
            val (apu, _) = takeSamples(s.apu, sampleBuf, sampleBuf.size / 2)
            s = s.copy(apu = apu)
            frames++
        }
    }

    private fun mask(spec: String): Int = spec.fold(0) { acc, c ->
        acc or when (c.uppercaseChar()) {
            'U' -> Button.UP
            'D' -> Button.DOWN
            'L' -> Button.LEFT
            'R' -> Button.RIGHT
            'A' -> Button.A
            'B' -> Button.B
            'T' -> Button.START
            'E' -> Button.SELECT
            '-' -> 0
            else -> error("unknown button '$c'")
        }
    }

    private fun shot(path: String) {
        val img = BufferedImage(SCREEN_W * SCALE, SCREEN_H * SCALE, BufferedImage.TYPE_INT_RGB)
        val frame = s.ppu.frame
        for (y in 0 until SCREEN_H) {
            for (x in 0 until SCREEN_W) {
                val px = frame[y * SCREEN_W + x]
                for (dy in 0 until SCALE) {
                    for (dx in 0 until SCALE) {
                        img.setRGB(x * SCALE + dx, y * SCALE + dy, px)
                    }
                }
            }
        }
        val f = File(path)
        f.parentFile?.mkdirs()
        ImageIO.write(img, "png", f)
    }

    /** Debug dump for emulator diagnosis: OAM entries plus the PPU registers that gate sprites. */
    private fun oamDump(path: String) {
        val p = s.ppu
        val sb = StringBuilder()
        sb.append("lcdc=%02X scx=%d scy=%d wx=%d wy=%d opri=%d cgb=%b\n".format(p.lcdc, p.scx, p.scy, p.wx, p.wy, p.opri, p.cgb))
        for (i in 0 until 40) {
            val y = s.oam[i * 4].toInt() and 0xFF
            val x = s.oam[i * 4 + 1].toInt() and 0xFF
            val tile = s.oam[i * 4 + 2].toInt() and 0xFF
            val attr = s.oam[i * 4 + 3].toInt() and 0xFF
            if (y in 1..159 && x in 1..167) {
                sb.append("oam[%02d] y=%3d x=%3d tile=%02X attr=%02X screen=(%d,%d)\n".format(i, y, x, tile, attr, x - 8, y - 16))
            }
        }
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeText(sb.toString())
    }

    /** Debug dump for emulator diagnosis: raw VRAM, both banks (0x4000 bytes). */
    private fun vramDump(path: String) {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeBytes(s.vram)
    }

    // Pokemon Yellow WRAM (from pokeyellow ram/wram.asm "Main Data" section).
    private val wCurMap = 0xD35D
    private val wYCoord = 0xD360
    private val wXCoord = 0xD361

    /**
     * Player map id and coordinates, appended to the reply. Read-only navigation
     * aid: it observes WRAM the way the on-screen HUD would, and never writes.
     * Also dumps the raw D35A..D366 window so the exact field offsets can be
     * re-confirmed if a different ROM revision shifts them.
     */
    private fun pos() {
        extra.append(" map=%d x=%d y=%d".format(peek(s, wCurMap), peek(s, wXCoord), peek(s, wYCoord)))
        extra.append(" | D35A:")
        for (a in 0xD35A..0xD366) extra.append(" %02X".format(peek(s, a)))
    }

    /** Read <len> bytes (default 1) from CPU address space onto the reply line. */
    private fun peekMem(addrSpec: String, lenSpec: String?) {
        val addr = Integer.parseInt(addrSpec.removePrefix("0x").removePrefix("$"), 16)
        val len = lenSpec?.toInt() ?: 1
        extra.append(" %04X:".format(addr))
        for (i in 0 until len) extra.append(" %02X".format(peek(s, (addr + i) and 0xFFFF)))
    }

    fun extraAndClear(): String = extra.toString().also { extra = StringBuilder() }

    fun flushSave() {
        batteryRam(s.cart).fold({}, { image ->
            val hash = image.contentHashCode()
            if (hash != lastSaveHash) {
                saveFile.writeBytes(image)
                lastSaveHash = hash
            }
        })
    }

    /** Executes one command; returns false on quit. */
    fun execute(cmd: String): Boolean {
        val parts = cmd.trim().split(Regex("\\s+"))
        when (parts[0]) {
            "p" -> {
                held = mask(parts[1])
                step(parts[2].toInt())
                held = 0
            }
            "s" -> step(parts[1].toInt())
            "m" -> {
                val m = mask(parts[1])
                repeat(parts[2].toInt()) {
                    held = m
                    step(parts[3].toInt())
                    held = 0
                    step(parts[4].toInt())
                }
            }
            "shot" -> shot(parts[1])
            "pos" -> pos() // navigation: report player map id + coords on the reply
            "peek" -> peekMem(parts[1], parts.getOrNull(2)) // navigation/diagnosis: read-only memory
            "oam" -> oamDump(parts[1]) // debug: dump OAM + PPU regs for emulator diagnosis
            "vram" -> vramDump(parts[1]) // debug: dump raw VRAM (both banks)
            "save" -> flushSave()
            "quit" -> return false
            else -> error("unknown command '${parts[0]}'")
        }
        return true
    }

    fun total() = frames
}

fun main(args: Array<String>) {
    val romFile = File(args.firstOrNull() ?: "Pokemon Yellow.gbc")
    require(romFile.exists()) { "ROM not found: ${romFile.absolutePath}" }
    val port = args.getOrNull(1)?.toInt() ?: System.getenv("GBC_PORT")?.toInt() ?: 8765
    val harness = Harness(romFile)

    ServerSocket(port).use { server ->
        println("harness listening on $port")
        while (true) {
            server.accept().use { sock ->
                // a client that connects and never sends a line must not wedge the server
                sock.soTimeout = 30_000
                val line = try {
                    sock.getInputStream().bufferedReader().readLine() ?: return@use
                } catch (_: java.net.SocketTimeoutException) {
                    return@use
                }
                val out = sock.getOutputStream().bufferedWriter()
                try {
                    var alive = true
                    line.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                        if (alive) alive = harness.execute(it)
                    }
                    harness.flushSave()
                    out.write("ok ${harness.total()}${harness.extraAndClear()}\n")
                    out.flush()
                    if (!alive) return
                } catch (e: Exception) {
                    out.write("err ${e.message}\n")
                    out.flush()
                }
            }
        }
    }
}
