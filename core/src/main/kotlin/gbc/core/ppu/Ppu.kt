package gbc.core.ppu

/** Hardware mode numbers are the ordinals: HBlank=0, VBlank=1, OamScan=2, Transfer=3. */
enum class PpuMode { HBlank, VBlank, OamScan, Transfer }

const val SCREEN_W = 160
const val SCREEN_H = 144
private const val LINE_DOTS = 456
private const val LAST_LINE = 153

/** DMG shades as packed RGB, matching the dmg-acid2 reference convention. */
private val SHADES = intArrayOf(0xFFFFFF, 0xAAAAAA, 0x555555, 0x000000)

data class PpuState(
    val lcdc: Int = 0x91,
    val statEnables: Int = 0, // STAT bits 3..6 as last written
    val scy: Int = 0,
    val scx: Int = 0,
    val ly: Int = 0,
    val lyc: Int = 0,
    val bgp: Int = 0xFC,
    val obp0: Int = 0xFF,
    val obp1: Int = 0xFF,
    val wy: Int = 0,
    val wx: Int = 0,
    val mode: PpuMode = PpuMode.OamScan,
    val dot: Int = 0,
    val statLine: Boolean = false,
    /** Latched LY==LYC comparison: frozen while the LCD is off (stat_lyc_onoff). */
    val lycFlag: Boolean = true,
    /** First line after LCD enable reports mode 0 during its OAM period. */
    val firstLine: Boolean = false,
    val windowLine: Int = 0,
    val windowTriggered: Boolean = false,
    val frame: IntArray = IntArray(SCREEN_W * SCREEN_H),
    val frameReady: Boolean = false,
    // Mode-3 pipeline, persisted across tick boundaries:
    val lx: Int = 0,
    val discard: Int = 0, // SCX fine-scroll pixels still to drop
    val fetchPhase: Int = 0,
    val fetchTileX: Int = 0,
    val fetchWindow: Boolean = false,
    val tileNum: Int = 0,
    val tileLo: Int = 0,
    val tileHi: Int = 0,
    val bgFifo: Int = 0, // 2 bits/pixel, head at the low end
    val bgFifoLen: Int = 0,
    val objFifo: Int = 0, // 4 bits/pixel: color(2) | palette<<2 | bgPriority<<3
    val objFifoLen: Int = 0,
    val sprites: IntArray = IntArray(10), // packed y | x<<8 | tile<<16 | attr<<24
    val spriteCount: Int = 0,
    val spriteDone: Int = 0, // bitmask: sprite already fetched this line
    val spriteFetchDots: Int = 0, // remaining stall dots; sprite index in spriteFetchIdx
    val spriteFetchIdx: Int = -1,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

class PpuTick(val ppu: PpuState, val irqVblank: Boolean, val irqStat: Boolean)

fun statRead(p: PpuState): Int =
    0x80 or p.statEnables or (if (p.lycFlag) 0x04 else 0) or p.mode.ordinal

class StatUpdate(val ppu: PpuState, val irq: Boolean)

/**
 * The composite STAT interrupt line. Rising edges request the interrupt;
 * a condition becoming true while the line is already high is swallowed
 * (the blocking quirk). On VBlank entry the OAM-scan source also lifts the
 * line for a moment (hardware quirk, vblank_stat_intr).
 */
private fun statComposite(enables: Int, mode: PpuMode, lycFlag: Boolean, vblankEntry: Boolean): Boolean =
    (enables and 0x08 != 0 && mode == PpuMode.HBlank) ||
        (enables and 0x10 != 0 && mode == PpuMode.VBlank) ||
        (enables and 0x20 != 0 && (mode == PpuMode.OamScan || vblankEntry)) ||
        (enables and 0x40 != 0 && lycFlag)

/**
 * Writing STAT recomputes the interrupt line immediately and can fire an edge.
 * On DMG the write also behaves as if 0xFF were written for one moment (the
 * STAT write glitch that crashed Road Rash), so all sources briefly enable.
 */
fun statWrite(p: PpuState, value: Int, dmgGlitch: Boolean): StatUpdate {
    if (p.lcdc and 0x80 == 0) return StatUpdate(p.copy(statEnables = value and 0x78), irq = false)
    var line = p.statLine
    var irq = false
    if (dmgGlitch) {
        val glitchLine = statComposite(0x78, p.mode, p.lycFlag, vblankEntry = false)
        irq = glitchLine && !line
        line = glitchLine
    }
    val newLine = statComposite(value and 0x78, p.mode, p.lycFlag, vblankEntry = false)
    irq = irq || (newLine && !line)
    return StatUpdate(p.copy(statEnables = value and 0x78, statLine = newLine), irq)
}

/**
 * LYC writes only store the value; the comparison latch updates on the next
 * dot of the comparison clock — and not at all while the LCD is off.
 */
fun lycWrite(p: PpuState, value: Int): PpuState = p.copy(lyc = value)

fun lcdcWrite(p: PpuState, value: Int): StatUpdate {
    val wasOn = p.lcdc and 0x80 != 0
    val nowOn = value and 0x80 != 0
    return when {
        // Off: LY/mode reset; the LYC latch and STAT line are RETAINED (stat_lyc_onoff)
        wasOn && !nowOn -> StatUpdate(p.copy(lcdc = value, ly = 0, dot = 0, mode = PpuMode.HBlank), irq = false)
        // On: the comparison clock restarts immediately and can fire a STAT edge
        // before the write instruction ends; the first line reports mode 0.
        !wasOn && nowOn -> {
            val lycFlag = p.lyc == 0
            val line = statComposite(p.statEnables, PpuMode.HBlank, lycFlag, vblankEntry = false)
            StatUpdate(
                p.copy(
                    lcdc = value, ly = 0, dot = 0, mode = PpuMode.HBlank,
                    lycFlag = lycFlag, firstLine = true, statLine = line,
                    windowLine = 0, windowTriggered = false,
                ),
                irq = line && !p.statLine,
            )
        }
        else -> StatUpdate(p.copy(lcdc = value), irq = false)
    }
}

fun ppuTick(p: PpuState, vram: ByteArray, oam: ByteArray, dots: Int): PpuTick {
    if (p.lcdc and 0x80 == 0) return PpuTick(p, irqVblank = false, irqStat = false)
    val run = PpuRun(p, vram, oam)
    repeat(dots) { run.dot() }
    return PpuTick(run.toState(), run.irqVblank, run.irqStat)
}

@Suppress("TooManyFunctions")
private class PpuRun(s: PpuState, private val vram: ByteArray, private val oam: ByteArray) {
    private val lcdc = s.lcdc
    private val statEnables = s.statEnables
    private val scy = s.scy
    private val scx = s.scx
    private var ly = s.ly
    private val lyc = s.lyc
    private val bgp = s.bgp
    private val obp0 = s.obp0
    private val obp1 = s.obp1
    private val wy = s.wy
    private val wx = s.wx
    private var mode = s.mode
    private var dotInLine = s.dot
    private var statLine = s.statLine
    private var lycFlag = s.lycFlag
    private var firstLine = s.firstLine
    private var windowLine = s.windowLine
    private var windowTriggered = s.windowTriggered
    private val frame = s.frame
    private var frameReady = s.frameReady
    private var lx = s.lx
    private var discard = s.discard
    private var fetchPhase = s.fetchPhase
    private var fetchTileX = s.fetchTileX
    private var fetchWindow = s.fetchWindow
    private var tileNum = s.tileNum
    private var tileLo = s.tileLo
    private var tileHi = s.tileHi
    private var bgFifo = s.bgFifo
    private var bgFifoLen = s.bgFifoLen
    private var objFifo = s.objFifo
    private var objFifoLen = s.objFifoLen
    private val sprites = s.sprites
    private var spriteCount = s.spriteCount
    private var spriteDone = s.spriteDone
    private var spriteFetchDots = s.spriteFetchDots
    private var spriteFetchIdx = s.spriteFetchIdx
    var irqVblank = false
    var irqStat = false

    fun toState() = PpuState(
        lcdc, statEnables, scy, scx, ly, lyc, bgp, obp0, obp1, wy, wx, mode, dotInLine,
        statLine, lycFlag, firstLine, windowLine, windowTriggered, frame, frameReady,
        lx, discard, fetchPhase, fetchTileX, fetchWindow, tileNum, tileLo, tileHi,
        bgFifo, bgFifoLen, objFifo, objFifoLen, sprites, spriteCount, spriteDone,
        spriteFetchDots, spriteFetchIdx,
    )

    fun dot() {
        if (dotInLine == 0) startLine()
        // Hardware compares WY==LY continuously; once equal anywhere in the
        // frame the window is armed (acid2 writes WY mid-line via LYC IRQs).
        if (ly == wy) windowTriggered = true
        if (ly < SCREEN_H) {
            if (dotInLine == 80) startTransfer()
            if (mode == PpuMode.Transfer) transferDot()
        }
        dotInLine++
        if (dotInLine == LINE_DOTS) endLine()
        updateStatLine()
    }

    private fun startLine() {
        if (ly < SCREEN_H) {
            // The first line after LCD enable keeps mode reading 0 through its OAM period
            mode = if (firstLine) PpuMode.HBlank else PpuMode.OamScan
            scanSprites()
        } else if (ly == SCREEN_H) {
            mode = PpuMode.VBlank
            irqVblank = true
            frameReady = true
        }
    }

    private fun endLine() {
        dotInLine = 0
        firstLine = false
        if (fetchWindow) windowLine++
        fetchWindow = false
        ly++
        if (ly > LAST_LINE) {
            ly = 0
            windowLine = 0
            windowTriggered = false
        }
    }

    private fun scanSprites() {
        spriteCount = 0
        spriteDone = 0
        spriteFetchIdx = -1
        spriteFetchDots = 0
        val h = if (lcdc and 0x04 != 0) 16 else 8
        var i = 0
        while (i < 40 && spriteCount < 10) {
            val y = oam[i * 4].toInt() and 0xFF
            val row = ly + 16 - y
            if (row in 0 until h) {
                sprites[spriteCount++] = y or
                    ((oam[i * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((oam[i * 4 + 2].toInt() and 0xFF) shl 16) or
                    ((oam[i * 4 + 3].toInt() and 0xFF) shl 24)
            }
            i++
        }
    }

    private fun startTransfer() {
        mode = PpuMode.Transfer
        lx = 0
        discard = scx and 7
        fetchPhase = 0
        fetchTileX = 0
        fetchWindow = false
        bgFifo = 0
        bgFifoLen = 0
        objFifo = 0
        objFifoLen = 0
    }

    private fun transferDot() {
        maybeStartWindow()
        if (spriteFetchIdx >= 0) {
            if (--spriteFetchDots == 0) {
                loadSprite(spriteFetchIdx)
                spriteFetchIdx = -1
            }
            return // BG fetch and pixel output stall during the sprite fetch
        }
        if (maybeTriggerSprite()) return
        fetcherStep()
        shiftPixel()
    }

    private fun maybeStartWindow() {
        if (!fetchWindow && windowTriggered && lcdc and 0x20 != 0 && lx >= wx - 7) {
            fetchWindow = true
            fetchPhase = 0
            fetchTileX = 0
            bgFifo = 0
            bgFifoLen = 0
        }
    }

    private fun maybeTriggerSprite(): Boolean {
        if (lcdc and 0x02 == 0) return false
        for (i in 0 until spriteCount) {
            if (spriteDone and (1 shl i) != 0) continue
            val x = (sprites[i] shr 8) and 0xFF
            if (maxOf(0, x - 8) == lx) {
                spriteDone = spriteDone or (1 shl i)
                spriteFetchIdx = i
                spriteFetchDots = 6
                return true
            }
        }
        return false
    }

    private fun loadSprite(i: Int) {
        val packed = sprites[i]
        val y = packed and 0xFF
        val x = (packed shr 8) and 0xFF
        var tile = (packed shr 16) and 0xFF
        val attr = (packed shr 24) and 0xFF
        val tall = lcdc and 0x04 != 0
        var row = ly + 16 - y
        if (attr and 0x40 != 0) row = (if (tall) 15 else 7) - row // Y flip
        if (tall) tile = (tile and 0xFE) or (row shr 3)
        val addr = tile * 16 + (row and 7) * 2
        val lo = vram[addr].toInt() and 0xFF
        val hi = vram[addr + 1].toInt() and 0xFF
        val xflip = attr and 0x20 != 0
        val skip = maxOf(0, 8 - x) // partially off the left edge
        var fifo = objFifo
        var len = objFifoLen
        for (px in skip until 8) {
            val bit = if (xflip) px else 7 - px
            val color = ((lo shr bit) and 1) or (((hi shr bit) and 1) shl 1)
            val slot = px - skip
            val pixel = color or (((attr shr 4) and 1) shl 2) or (((attr shr 7) and 1) shl 3)
            if (slot < len) {
                // merge: only fill slots whose existing pixel is transparent
                if ((fifo shr (slot * 4)) and 3 == 0) {
                    fifo = (fifo and (0xF shl (slot * 4)).inv()) or (pixel shl (slot * 4))
                }
            } else {
                // grow the fifo with transparent padding up to this slot
                while (len < slot) len++
                fifo = fifo or (pixel shl (slot * 4))
                len++
            }
        }
        objFifo = fifo
        objFifoLen = maxOf(objFifoLen, len)
    }

    private fun fetcherStep() {
        when (fetchPhase) {
            1 -> tileNum = fetchTileNum()
            3 -> tileLo = fetchTileData(0)
            5 -> tileHi = fetchTileData(1)
        }
        if (fetchPhase >= 6) {
            if (bgFifoLen == 0) {
                pushBgRow()
                fetchTileX++
                fetchPhase = 0
            }
            // else: stay in the push phase until the FIFO drains
        } else {
            fetchPhase++
        }
    }

    private fun fetchTileNum(): Int {
        val mapBit = if (fetchWindow) 0x40 else 0x08
        val base = if (lcdc and mapBit != 0) 0x1C00 else 0x1800
        val row = if (fetchWindow) windowLine else (ly + scy) and 0xFF
        val tx = if (fetchWindow) fetchTileX and 31 else ((scx shr 3) + fetchTileX) and 31
        return vram[base + (row shr 3) * 32 + tx].toInt() and 0xFF
    }

    private fun fetchTileData(plane: Int): Int {
        val row = if (fetchWindow) windowLine else (ly + scy) and 0xFF
        val fineY = row and 7
        val addr = if (lcdc and 0x10 != 0) {
            tileNum * 16 + fineY * 2 + plane
        } else {
            0x1000 + tileNum.toByte().toInt() * 16 + fineY * 2 + plane
        }
        return vram[addr].toInt() and 0xFF
    }

    private fun pushBgRow() {
        var fifo = 0
        for (px in 0..7) {
            val bit = 7 - px
            val color = ((tileLo shr bit) and 1) or (((tileHi shr bit) and 1) shl 1)
            fifo = fifo or (color shl (px * 2))
        }
        bgFifo = fifo
        bgFifoLen = 8
    }

    private fun shiftPixel() {
        if (bgFifoLen == 0) return
        val bgColor = bgFifo and 3
        bgFifo = bgFifo ushr 2 // logical shift: slot 7's bits can occupy the sign bit
        bgFifoLen--
        var objColor = 0
        var objPal = 0
        var objPrio = false
        if (objFifoLen > 0) {
            val o = objFifo and 0xF
            objFifo = objFifo ushr 4 // logical shift: a behind-BG pixel in slot 7 sets bit 31
            objFifoLen--
            objColor = o and 3
            objPal = (o shr 2) and 1
            objPrio = o and 8 != 0
        }
        if (discard > 0) {
            discard--
            return
        }
        val bgIdx = if (lcdc and 0x01 != 0) bgColor else 0
        val shade = if (objColor != 0 && (!objPrio || bgIdx == 0)) {
            val pal = if (objPal == 1) obp1 else obp0
            SHADES[(pal shr (objColor * 2)) and 3]
        } else {
            SHADES[(bgp shr (bgIdx * 2)) and 3]
        }
        frame[ly * SCREEN_W + lx] = shade
        lx++
        if (lx == SCREEN_W) mode = PpuMode.HBlank
    }

    private fun updateStatLine() {
        lycFlag = ly == lyc // the comparison clock runs whenever the PPU does
        val vblankEntry = mode == PpuMode.VBlank && ly == SCREEN_H && dotInLine <= 1
        val line = statComposite(statEnables, mode, lycFlag, vblankEntry)
        if (line && !statLine) irqStat = true
        statLine = line
    }
}
