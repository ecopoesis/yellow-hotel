package gbc.core.serial

/**
 * Link-port shift register. An internally-clocked transfer (SC=0x81) shifts
 * one bit every 512 T-cycles (8192 Hz); with nothing on the other end, 1s
 * shift in. Externally-clocked transfers wait forever (no link partner).
 */
data class SerialState(
    val data: Int = 0x00, // FF01 SB
    val ctrl: Int = 0x7E, // FF02 SC (unused bits read as 1)
    val outgoing: Int = 0, // byte latched when the transfer started
    val bitsLeft: Int = 0,
    val tAccum: Int = 0,
)

class SerialTick(val serial: SerialState, val irq: Boolean, val emitted: Int /* -1 = none */)

private const val T_PER_BIT = 512

fun serialTick(s: SerialState, tCycles: Int): SerialTick {
    if (s.ctrl and 0x81 != 0x81 || s.bitsLeft == 0) return SerialTick(s, irq = false, emitted = -1)
    var acc = s.tAccum + tCycles
    var data = s.data
    var bits = s.bitsLeft
    while (acc >= T_PER_BIT && bits > 0) {
        acc -= T_PER_BIT
        data = ((data shl 1) or 1) and 0xFF
        bits--
    }
    return if (bits == 0) {
        SerialTick(
            s.copy(data = data, ctrl = s.ctrl and 0x7F, bitsLeft = 0, tAccum = 0),
            irq = true,
            emitted = s.outgoing,
        )
    } else {
        SerialTick(s.copy(data = data, bitsLeft = bits, tAccum = acc), irq = false, emitted = -1)
    }
}

fun serialCtrlWrite(s: SerialState, value: Int): SerialState =
    if (value and 0x81 == 0x81) {
        s.copy(ctrl = value, outgoing = s.data, bitsLeft = 8, tAccum = 0)
    } else {
        s.copy(ctrl = value)
    }
