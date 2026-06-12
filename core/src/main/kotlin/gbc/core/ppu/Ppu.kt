package gbc.core.ppu

/** Hardware mode numbers are the ordinals: HBlank=0, VBlank=1, OamScan=2, Transfer=3. */
enum class PpuMode { HBlank, VBlank, OamScan, Transfer }

const val SCREEN_W = 160
const val SCREEN_H = 144
private const val LINE_DOTS = 456
private const val LAST_LINE = 153

/** DMG shades as packed RGB, matching the dmg-acid2 reference convention. */
private val SHADES = intArrayOf(0xFFFFFF, 0xAAAAAA, 0x555555, 0x000000)

/**
 * FIFO pixel layout (8 bits per slot, head at the low end):
 * bits 0-1 color, bits 2-4 palette, bit 5 priority
 * (BG: tile-attr priority; OBJ: behind-BG flag. DMG OBJ palette is bit 2.)
 */
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
    // CGB:
    val cgb: Boolean = false,
    val vbk: Int = 0,
    val bcps: Int = 0,
    val ocps: Int = 0,
    val opri: Int = 0,
    val bgPal: ByteArray = ByteArray(64) { -1 }, // boot: white
    val objPal: ByteArray = ByteArray(64) { -1 },
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
    val fetchAttr: Int = 0,
    val tileNum: Int = 0,
    val tileLo: Int = 0,
    val tileHi: Int = 0,
    val bgFifo: Long = 0,
    val bgFifoLen: Int = 0,
    val objFifo: Long = 0,
    val objFifoLen: Int = 0,
    val objFifoSrc: Int = 0, // 4 bits/slot: scan index of the owning sprite
    val sprites: IntArray = IntArray(10), // packed y | x<<8 | tile<<16 | attr<<24
    val spriteCount: Int = 0,
    val spriteDone: Int = 0, // bitmask: sprite already fetched this line
    val spriteFetchDots: Int = 0, // remaining stall dots
    val spriteFetchIdx: Int = -1,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

class PpuTick(val ppu: PpuState, val irqVblank: Boolean, val irqStat: Boolean)

class StatUpdate(val ppu: PpuState, val irq: Boolean)

fun statRead(p: PpuState): Int =
    0x80 or p.statEnables or (if (p.lycFlag) 0x04 else 0) or p.mode.ordinal

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

// CGB palette RAM ports: index register with auto-increment on data writes.

fun bcpsWrite(p: PpuState, value: Int): PpuState = p.copy(bcps = value and 0xBF)

fun ocpsWrite(p: PpuState, value: Int): PpuState = p.copy(ocps = value and 0xBF)

fun bcpdWrite(p: PpuState, value: Int): PpuState {
    p.bgPal[p.bcps and 0x3F] = value.toByte()
    return if (p.bcps and 0x80 != 0) p.copy(bcps = 0x80 or ((p.bcps + 1) and 0x3F)) else p
}

fun ocpdWrite(p: PpuState, value: Int): PpuState {
    p.objPal[p.ocps and 0x3F] = value.toByte()
    return if (p.ocps and 0x80 != 0) p.copy(ocps = 0x80 or ((p.ocps + 1) and 0x3F)) else p
}

fun bcpdRead(p: PpuState): Int = p.bgPal[p.bcps and 0x3F].toInt() and 0xFF

fun ocpdRead(p: PpuState): Int = p.objPal[p.ocps and 0x3F].toInt() and 0xFF

/** RGB555 little-endian palette entry to packed RGB888, per the acid2 formula. */
private fun rgb555(pal: ByteArray, palIdx: Int, color: Int): Int {
    val offset = palIdx * 8 + color * 2
    val raw = (pal[offset].toInt() and 0xFF) or ((pal[offset + 1].toInt() and 0xFF) shl 8)
    val r = raw and 0x1F
    val g = (raw shr 5) and 0x1F
    val b = (raw shr 10) and 0x1F
    return (((r shl 3) or (r shr 2)) shl 16) or (((g shl 3) or (g shr 2)) shl 8) or ((b shl 3) or (b shr 2))
}

fun ppuTick(p: PpuState, vram: ByteArray, oam: ByteArray, dots: Int): PpuTick {
    if (p.lcdc and 0x80 == 0) return PpuTick(p, irqVblank = false, irqStat = false)
    val run = PpuRun(p, vram, oam)
    repeat(dots) { run.dot() }
    return PpuTick(run.toState(), run.irqVblank, run.irqStat)
}

/**
 * Mutable working state for the PPU, reusable across many dots so the bus can
 * amortize state materialization over a whole CPU instruction. Per-dot
 * semantics are identical to calling [ppuTick] once per M-cycle.
 */
@Suppress("TooManyFunctions")
internal class PpuRun(s: PpuState, private val vram: ByteArray, private val oam: ByteArray) {
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
    private val cgb = s.cgb
    private val vbkKeep = s.vbk
    private val bcpsKeep = s.bcps
    private val ocpsKeep = s.ocps
    private val opri = s.opri
    private val bgPal = s.bgPal
    private val objPal = s.objPal
    private var windowLine = s.windowLine
    private var windowTriggered = s.windowTriggered
    private val frame = s.frame
    private var frameReady = s.frameReady
    private var lx = s.lx
    private var discard = s.discard
    private var fetchPhase = s.fetchPhase
    private var fetchTileX = s.fetchTileX
    private var fetchWindow = s.fetchWindow
    private var fetchAttr = s.fetchAttr
    private var tileNum = s.tileNum
    private var tileLo = s.tileLo
    private var tileHi = s.tileHi
    private var bgFifo = s.bgFifo
    private var bgFifoLen = s.bgFifoLen
    private var objFifo = s.objFifo
    private var objFifoLen = s.objFifoLen
    private var objFifoSrc = s.objFifoSrc
    private val sprites = s.sprites
    private var spriteCount = s.spriteCount
    private var spriteDone = s.spriteDone
    private var spriteFetchDots = s.spriteFetchDots
    private var spriteFetchIdx = s.spriteFetchIdx
    var irqVblank = false
    var irqStat = false

    /** Live views for the bus: cheap reads that do not require materializing. */
    val modeNow get() = mode
    val lyNow get() = ly
    val lycFlagNow get() = lycFlag
    val frameReadyNow get() = frameReady

    /** Returns pending IRQs as IF bits (vblank=1, stat=2) and clears them. */
    fun consumeIrqs(): Int {
        var bits = 0
        if (irqVblank) bits = bits or 0x01
        if (irqStat) bits = bits or 0x02
        irqVblank = false
        irqStat = false
        return bits
    }

    fun toState() = PpuState(
        lcdc, statEnables, scy, scx, ly, lyc, bgp, obp0, obp1, wy, wx, mode, dotInLine,
        statLine, lycFlag, firstLine, cgb, vbkKeep, bcpsKeep, ocpsKeep, opri, bgPal, objPal,
        windowLine, windowTriggered, frame, frameReady, lx, discard, fetchPhase, fetchTileX,
        fetchWindow, fetchAttr, tileNum, tileLo, tileHi, bgFifo, bgFifoLen, objFifo,
        objFifoLen, objFifoSrc, sprites, spriteCount, spriteDone, spriteFetchDots, spriteFetchIdx,
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
        objFifoSrc = 0
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
        val bank = if (cgb) (attr shr 3) and 1 else 0
        val addr = bank * 0x2000 + tile * 16 + (row and 7) * 2
        val lo = vram[addr].toInt() and 0xFF
        val hi = vram[addr + 1].toInt() and 0xFF
        val xflip = attr and 0x20 != 0
        val palette = if (cgb) attr and 0x07 else (attr shr 4) and 1
        val skip = maxOf(0, 8 - x) // partially off the left edge
        // On CGB with OPRI=0 a lower OAM index beats anything already merged;
        // otherwise (DMG, or OPRI=1) only transparent slots may be filled.
        val oamPriority = cgb && opri and 1 == 0
        for (px in skip until 8) {
            val bit = if (xflip) px else 7 - px
            val color = ((lo shr bit) and 1) or (((hi shr bit) and 1) shl 1)
            val slot = px - skip
            val pixel = (color or (palette shl 2) or (((attr shr 7) and 1) shl 5)).toLong()
            val shift = slot * 8
            if (slot < objFifoLen) {
                val existingColor = (objFifo ushr shift) and 3
                val existingSrc = (objFifoSrc ushr (slot * 4)) and 0xF
                val replace = existingColor == 0L || (oamPriority && color != 0 && i < existingSrc)
                if (replace) {
                    objFifo = (objFifo and (0xFFL shl shift).inv()) or (pixel shl shift)
                    objFifoSrc = (objFifoSrc and (0xF shl (slot * 4)).inv()) or (i shl (slot * 4))
                }
            } else {
                objFifo = objFifo or (pixel shl shift)
                objFifoSrc = objFifoSrc or (i shl (slot * 4))
                objFifoLen = slot + 1
            }
        }
    }

    private fun fetcherStep() {
        when (fetchPhase) {
            1 -> {
                tileNum = fetchTileNum()
                fetchAttr = if (cgb) fetchTileAttr() else 0
            }
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

    private fun mapIndex(): Int {
        val mapBit = if (fetchWindow) 0x40 else 0x08
        val base = if (lcdc and mapBit != 0) 0x1C00 else 0x1800
        val row = if (fetchWindow) windowLine else (ly + scy) and 0xFF
        val tx = if (fetchWindow) fetchTileX and 31 else ((scx shr 3) + fetchTileX) and 31
        return base + (row shr 3) * 32 + tx
    }

    private fun fetchTileNum(): Int = vram[mapIndex()].toInt() and 0xFF

    private fun fetchTileAttr(): Int = vram[0x2000 + mapIndex()].toInt() and 0xFF

    private fun fetchTileData(plane: Int): Int {
        val row = if (fetchWindow) windowLine else (ly + scy) and 0xFF
        var fineY = row and 7
        if (fetchAttr and 0x40 != 0) fineY = 7 - fineY // CGB BG Y flip
        val bank = (fetchAttr shr 3) and 1
        val addr = if (lcdc and 0x10 != 0) {
            tileNum * 16 + fineY * 2 + plane
        } else {
            0x1000 + tileNum.toByte().toInt() * 16 + fineY * 2 + plane
        }
        return vram[bank * 0x2000 + addr].toInt() and 0xFF
    }

    private fun pushBgRow() {
        val xflip = fetchAttr and 0x20 != 0
        val meta = ((fetchAttr and 0x07) shl 2) or (((fetchAttr shr 7) and 1) shl 5)
        var fifo = 0L
        for (px in 0..7) {
            val bit = if (xflip) px else 7 - px
            val color = ((tileLo shr bit) and 1) or (((tileHi shr bit) and 1) shl 1)
            fifo = fifo or ((color or meta).toLong() shl (px * 8))
        }
        bgFifo = fifo
        bgFifoLen = 8
    }

    private fun shiftPixel() {
        if (bgFifoLen == 0) return
        val bg = (bgFifo and 0xFF).toInt()
        bgFifo = bgFifo ushr 8
        bgFifoLen--
        var obj = 0
        if (objFifoLen > 0) {
            obj = (objFifo and 0xFF).toInt()
            objFifo = objFifo ushr 8
            objFifoSrc = objFifoSrc ushr 4
            objFifoLen--
        }
        if (discard > 0) {
            discard--
            return
        }
        frame[ly * SCREEN_W + lx] = if (cgb) mixCgb(bg, obj) else mixDmg(bg, obj)
        lx++
        if (lx == SCREEN_W) mode = PpuMode.HBlank
    }

    private fun mixDmg(bg: Int, obj: Int): Int {
        val bgIdx = if (lcdc and 0x01 != 0) bg and 3 else 0
        val objColor = obj and 3
        return if (objColor != 0 && (obj and 0x20 == 0 || bgIdx == 0)) {
            val pal = if (obj and 0x04 != 0) obp1 else obp0
            SHADES[(pal shr (objColor * 2)) and 3]
        } else {
            SHADES[(bgp shr (bgIdx * 2)) and 3]
        }
    }

    private fun mixCgb(bg: Int, obj: Int): Int {
        val bgColor = bg and 3
        val objColor = obj and 3
        // LCDC bit 0 on CGB is master priority: when clear, sprites always win
        val masterPriority = lcdc and 0x01 != 0
        val bgWins = masterPriority && bgColor != 0 && (bg and 0x20 != 0 || obj and 0x20 != 0)
        return if (objColor != 0 && !bgWins) {
            rgb555(objPal, (obj shr 2) and 7, objColor)
        } else {
            rgb555(bgPal, (bg shr 2) and 7, bgColor)
        }
    }

    private fun updateStatLine() {
        lycFlag = ly == lyc // the comparison clock runs whenever the PPU does
        val vblankEntry = mode == PpuMode.VBlank && ly == SCREEN_H && dotInLine <= 1
        val line = statComposite(statEnables, mode, lycFlag, vblankEntry)
        if (line && !statLine) irqStat = true
        statLine = line
    }
}
