package gbc.core.timer

enum class TimaReload { None, Pending, Reloading }

/**
 * DIV/TIMA share one 16-bit counter: DIV is its upper byte, and TIMA increments
 * on falling edges of the TAC-selected counter bit ANDed with the TAC enable —
 * which is what makes DIV writes and TAC changes able to tick TIMA (hardware
 * quirks the Mooneye timer suite checks).
 */
data class TimerState(
    val sysCounter: Int = 0,
    val tima: Int = 0,
    val tma: Int = 0,
    val tac: Int = 0,
    val reload: TimaReload = TimaReload.None,
)

class TimerTick(val timer: TimerState, val irq: Boolean)

private fun tacBit(tac: Int): Int = when (tac and 3) {
    0 -> 9
    1 -> 3
    2 -> 5
    else -> 7
}

private fun signal(counter: Int, tac: Int): Boolean =
    (tac and 4) != 0 && (counter shr tacBit(tac)) and 1 == 1

/** One M-cycle (4 T-cycles of CPU clock). The reload pipeline advances first. */
fun timerTick(t: TimerState): TimerTick {
    var tima = t.tima
    var reload = t.reload
    var irq = false
    when (t.reload) {
        TimaReload.Pending -> {
            tima = t.tma
            irq = true
            reload = TimaReload.Reloading
        }
        TimaReload.Reloading -> reload = TimaReload.None
        TimaReload.None -> {}
    }
    var counter = t.sysCounter
    repeat(4) {
        val before = signal(counter, t.tac)
        counter = (counter + 1) and 0xFFFF
        if (before && !signal(counter, t.tac)) {
            tima++
            if (tima > 0xFF) {
                tima = 0
                reload = TimaReload.Pending
            }
        }
    }
    return TimerTick(t.copy(sysCounter = counter, tima = tima, reload = reload), irq)
}

fun timerDivRead(t: TimerState): Int = (t.sysCounter shr 8) and 0xFF

/** Resetting the counter can drop the selected bit: a falling edge like any other. */
fun timerDivWrite(t: TimerState): TimerTick {
    val bumped = if (signal(t.sysCounter, t.tac)) bump(t) else t
    return TimerTick(bumped.copy(sysCounter = 0), false)
}

fun timerTimaWrite(t: TimerState, value: Int): TimerState = when (t.reload) {
    TimaReload.Pending -> t.copy(tima = value and 0xFF, reload = TimaReload.None) // cancels reload
    TimaReload.Reloading -> t // TMA wins during the reload cycle
    TimaReload.None -> t.copy(tima = value and 0xFF)
}

fun timerTmaWrite(t: TimerState, value: Int): TimerState {
    val v = value and 0xFF
    return if (t.reload == TimaReload.Reloading) t.copy(tma = v, tima = v) else t.copy(tma = v)
}

fun timerTacRead(t: TimerState): Int = 0xF8 or t.tac

/** DMG glitch: changing TAC while the selected signal is high can tick TIMA. */
fun timerTacWrite(t: TimerState, value: Int): TimerTick {
    val v = value and 0x07
    val glitched = if (signal(t.sysCounter, t.tac) && !signal(t.sysCounter, v)) bump(t) else t
    return TimerTick(glitched.copy(tac = v), false)
}

private fun bump(t: TimerState): TimerState {
    val next = t.tima + 1
    return if (next > 0xFF) t.copy(tima = 0, reload = TimaReload.Pending) else t.copy(tima = next)
}
