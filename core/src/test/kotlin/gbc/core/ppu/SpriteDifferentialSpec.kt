package gbc.core.ppu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * Differential fuzz: renders whole frames through the FIFO pipeline and
 * compares every pixel against an independent per-scanline reference renderer
 * written straight from Pandocs (OAM scan limit, sprite priority, palette and
 * priority mixing). Seeds are fixed so failures reproduce.
 *
 * The window is left disabled and SCX is kept 8-aligned: fine-scroll/window
 * interaction is out of scope here (this hunts sprite selection bugs of the
 * "invisible NPC" kind, which occur with the screen at rest).
 */
private val SHADES = intArrayOf(0xFFFFFF, 0xAAAAAA, 0x555555, 0x000000)

private fun rgb555(pal: ByteArray, palIdx: Int, color: Int): Int {
    val offset = palIdx * 8 + color * 2
    val raw = (pal[offset].toInt() and 0xFF) or ((pal[offset + 1].toInt() and 0xFF) shl 8)
    val r = raw and 0x1F
    val g = (raw shr 5) and 0x1F
    val b = (raw shr 10) and 0x1F
    return (((r shl 3) or (r shr 2)) shl 16) or (((g shl 3) or (g shr 2)) shl 8) or ((b shl 3) or (b shr 2))
}

internal class RefConfig(
    val lcdc: Int,
    val scx: Int,
    val scy: Int,
    val bgp: Int,
    val obp0: Int,
    val obp1: Int,
    val cgb: Boolean,
    val opri: Int,
    val bgPal: ByteArray,
    val objPal: ByteArray,
    val vram: ByteArray,
    val oam: ByteArray,
)

/** BG color index + attr at screen (x, y), ignoring the window. */
internal fun refBg(c: RefConfig, x: Int, y: Int): Pair<Int, Int> {
    if (!c.cgb && c.lcdc and 0x01 == 0) return 0 to 0
    val mapY = (y + c.scy) and 0xFF
    val mapX = (x + c.scx) and 0xFF
    val base = if (c.lcdc and 0x08 != 0) 0x1C00 else 0x1800
    val idx = base + (mapY shr 3) * 32 + (mapX shr 3)
    val tile = c.vram[idx].toInt() and 0xFF
    val attr = if (c.cgb) c.vram[0x2000 + idx].toInt() and 0xFF else 0
    var fineY = mapY and 7
    if (attr and 0x40 != 0) fineY = 7 - fineY
    var fineX = mapX and 7
    if (attr and 0x20 == 0) fineX = 7 - fineX
    val bank = (attr shr 3) and 1
    val addr = if (c.lcdc and 0x10 != 0) tile * 16 else 0x1000 + tile.toByte().toInt() * 16
    val lo = c.vram[bank * 0x2000 + addr + fineY * 2].toInt() and 0xFF
    val hi = c.vram[bank * 0x2000 + addr + fineY * 2 + 1].toInt() and 0xFF
    return (((lo shr fineX) and 1) or (((hi shr fineX) and 1) shl 1)) to attr
}

/** Winning sprite pixel at screen (x, y): color index, palette, behind-BG flag — or null. */
internal fun refObj(c: RefConfig, scanned: List<Int>, x: Int, y: Int): Triple<Int, Int, Boolean>? {
    if (c.lcdc and 0x02 == 0) return null
    val tall = c.lcdc and 0x04 != 0
    var winner: Triple<Int, Int, Boolean>? = null
    var winnerX = -1
    for (i in scanned) {
        val sy = c.oam[i * 4].toInt() and 0xFF
        val sx = c.oam[i * 4 + 1].toInt() and 0xFF
        if (x < sx - 8 || x >= sx) continue
        var tile = c.oam[i * 4 + 2].toInt() and 0xFF
        val attr = c.oam[i * 4 + 3].toInt() and 0xFF
        var row = y + 16 - sy
        if (attr and 0x40 != 0) row = (if (tall) 15 else 7) - row
        if (tall) tile = (tile and 0xFE) or (row shr 3)
        val bank = if (c.cgb) (attr shr 3) and 1 else 0
        val addr = bank * 0x2000 + tile * 16 + (row and 7) * 2
        val lo = c.vram[addr].toInt() and 0xFF
        val hi = c.vram[addr + 1].toInt() and 0xFF
        val px = x - (sx - 8)
        val bit = if (attr and 0x20 != 0) px else 7 - px
        val color = ((lo shr bit) and 1) or (((hi shr bit) and 1) shl 1)
        if (color == 0) continue
        val palette = if (c.cgb) attr and 0x07 else (attr shr 4) and 1
        val candidate = Triple(color, palette, attr and 0x80 != 0)
        val byIndex = c.cgb && c.opri and 1 == 0
        if (winner == null || (!byIndex && sx < winnerX)) {
            winner = candidate
            winnerX = sx
        }
        // byIndex: scanned is OAM-ordered, so the first hit already won
        if (byIndex) return winner
    }
    return winner
}

internal fun refRender(c: RefConfig): IntArray {
    val frame = IntArray(SCREEN_W * SCREEN_H)
    val h = if (c.lcdc and 0x04 != 0) 16 else 8
    for (y in 0 until SCREEN_H) {
        val scanned = (0 until 40).filter { (y + 16 - (c.oam[it * 4].toInt() and 0xFF)) in 0 until h }.take(10)
        for (x in 0 until SCREEN_W) {
            val (bgColor, bgAttr) = refBg(c, x, y)
            val obj = refObj(c, scanned, x, y)
            frame[y * SCREEN_W + x] = if (c.cgb) {
                val masterPriority = c.lcdc and 0x01 != 0
                val bgWins = masterPriority && bgColor != 0 && (bgAttr and 0x80 != 0 || obj?.third == true)
                if (obj != null && !bgWins) {
                    rgb555(c.objPal, obj.second, obj.first)
                } else {
                    rgb555(c.bgPal, bgAttr and 0x07, bgColor)
                }
            } else {
                if (obj != null && (!obj.third || bgColor == 0)) {
                    val pal = if (obj.second != 0) c.obp1 else c.obp0
                    SHADES[(pal shr (obj.first * 2)) and 3]
                } else {
                    SHADES[(c.bgp shr (bgColor * 2)) and 3]
                }
            }
        }
    }
    return frame
}

internal fun fifoRender(c: RefConfig): IntArray {
    var p = PpuState(
        lcdc = c.lcdc, scx = c.scx, scy = c.scy, ly = 0, dot = 0, mode = PpuMode.OamScan,
        bgp = c.bgp, obp0 = c.obp0, obp1 = c.obp1, wy = 144, wx = 7,
        cgb = c.cgb, opri = c.opri, bgPal = c.bgPal, objPal = c.objPal,
    )
    repeat(456 * 154 / 4) { p = ppuTick(p, c.vram, c.oam, 4).ppu }
    return p.frame.copyOf()
}

internal fun randomConfig(rng: Random, cgb: Boolean): RefConfig {
    val vram = ByteArray(0x4000).also { rng.nextBytes(it) }
    val oam = ByteArray(0xA0)
    for (i in 0 until 40) {
        // biased toward on-screen positions, with edge values well represented
        oam[i * 4] = when (rng.nextInt(8)) {
            0 -> rng.nextInt(0, 17).toByte() // partially above the top or hidden
            1 -> rng.nextInt(144, 170).toByte() // around the bottom edge
            else -> rng.nextInt(0, 176).toByte()
        }
        oam[i * 4 + 1] = when (rng.nextInt(8)) {
            0 -> rng.nextInt(0, 9).toByte() // left edge
            1 -> rng.nextInt(160, 176).toByte() // right edge
            else -> (rng.nextInt(0, 21) * 8).toByte() // grid-aligned like Pokemon
        }
        oam[i * 4 + 2] = rng.nextInt(256).toByte()
        oam[i * 4 + 3] = rng.nextInt(256).toByte()
    }
    return RefConfig(
        lcdc = 0x80 or (rng.nextInt(0x20) and 0x1F), // window off, random BG/OBJ bits
        scx = rng.nextInt(32) * 8,
        scy = rng.nextInt(256),
        bgp = rng.nextInt(256),
        obp0 = rng.nextInt(256),
        obp1 = rng.nextInt(256),
        cgb = cgb,
        opri = if (cgb) rng.nextInt(2) else 0,
        bgPal = ByteArray(64).also { rng.nextBytes(it) },
        objPal = ByteArray(64).also { rng.nextBytes(it) },
        vram = vram,
        oam = oam,
    )
}

/** Mismatch summary: count plus bounding box, to tell single-pixel quirks from missing sprites. */
private fun diffSummary(expect: IntArray, got: IntArray): String? {
    var count = 0
    var minX = SCREEN_W
    var maxX = -1
    var minY = SCREEN_H
    var maxY = -1
    for (i in expect.indices) {
        if (expect[i] != got[i]) {
            count++
            val x = i % SCREEN_W
            val y = i / SCREEN_W
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }
    return if (count == 0) null else "$count px in ($minX,$minY)..($maxX,$maxY)"
}

class SpriteDifferentialSpec : FunSpec({

    for (cgb in listOf(false, true)) {
        test("FIFO pipeline matches the reference renderer (cgb=$cgb, 500 seeded frames)") {
            val failures = mutableListOf<String>()
            for (seed in 0 until 500) {
                val c = randomConfig(Random(seed * 2 + if (cgb) 1 else 0), cgb)
                val diff = diffSummary(refRender(c), fifoRender(c))
                if (diff != null) {
                    failures += "seed=$seed lcdc=%02X scx=%d scy=%d opri=%d: $diff"
                        .format(c.lcdc, c.scx, c.scy, c.opri)
                }
            }
            failures.joinToString("\n") shouldBe ""
        }
    }
})
