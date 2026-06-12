package gbc.core.system

import gbc.core.cart.CartridgeState
import gbc.core.cart.CgbFlag
import gbc.core.cpu.CpuState
import gbc.core.dma.OamDmaState
import gbc.core.ppu.PpuState
import gbc.core.timer.TimerState

enum class HwMode { Dmg, Cgb }

data class InterruptState(
    val iff: Int = 0xE1, // IF: post-boot has VBlank already requested
    val ie: Int = 0x00,
)

data class SerialState(
    val data: Int = 0x00, // FF01 SB
    val ctrl: Int = 0x7E, // FF02 SC (unused bits read as 1)
)

/**
 * Whole-machine state. Register/control state is immutable; the byte arrays are
 * bulk buffers owned by this value and mutated only inside step functions —
 * stepping invalidates the previous SystemState value.
 */
data class SystemState(
    val cpu: CpuState,
    val cart: CartridgeState,
    val intr: InterruptState,
    val serial: SerialState,
    val timer: TimerState,
    val dma: OamDmaState,
    val ppu: PpuState,
    val wram: ByteArray,  // 8 x 4 KiB; DMG uses banks 0-1
    val hram: ByteArray,  // 127 bytes
    val vram: ByteArray,  // 2 x 8 KiB; plain RAM until the PPU owns it (M4)
    val oam: ByteArray,   // 160 bytes
    val io: ByteArray,    // 0xFF00..0xFF7F raw backing until components claim registers
    val mode: HwMode,
    val tCycles: Long = 0,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Hardware mode the cart selects on a GBC: CGB-aware carts boot in CGB mode. */
fun defaultMode(cart: CartridgeState): HwMode = when (cart.header.cgbFlag) {
    CgbFlag.DmgOnly -> HwMode.Dmg
    CgbFlag.CgbCompatible, CgbFlag.CgbOnly -> HwMode.Cgb
}

/**
 * Machine state as the boot ROM leaves it (Pan Docs post-boot tables); we ship
 * no boot ROM. CGB mode sets A=0x11, which CGB-aware games check.
 */
fun postBootState(cart: CartridgeState, mode: HwMode = defaultMode(cart)): SystemState {
    val cpu = when (mode) {
        HwMode.Dmg -> CpuState(
            a = 0x01, f = 0xB0, b = 0x00, c = 0x13, d = 0x00, e = 0xD8, h = 0x01, l = 0x4D,
            sp = 0xFFFE, pc = 0x0100,
        )
        HwMode.Cgb -> CpuState(
            a = 0x11, f = 0x80, b = 0x00, c = 0x00, d = 0xFF, e = 0x56, h = 0x00, l = 0x0D,
            sp = 0xFFFE, pc = 0x0100,
        )
    }
    val io = ByteArray(0x80)
    io[0x00] = 0xCF.toByte() // P1: nothing pressed
    return SystemState(
        cpu = cpu,
        cart = cart,
        intr = InterruptState(),
        serial = SerialState(),
        // DIV reads ~0xAB right after the DMG boot ROM hands over (exact value: M9, boot_div)
        timer = TimerState(sysCounter = 0xAB00),
        dma = OamDmaState(),
        ppu = PpuState(),
        wram = ByteArray(8 * 0x1000),
        hram = ByteArray(0x7F),
        vram = ByteArray(2 * 0x2000),
        oam = ByteArray(0xA0),
        io = io,
        mode = mode,
    )
}
