package gbc.fixtures

import gbc.core.api.Ports
import gbc.core.api.SerialOut
import gbc.core.cart.parseCartridge
import gbc.core.system.HwMode
import gbc.core.system.SystemState
import gbc.core.system.peek
import gbc.core.system.postBootState
import gbc.core.system.stepInstruction

object Headless {

    const val CYCLES_PER_SECOND = 4_194_304L

    fun boot(rom: ByteArray, mode: HwMode = HwMode.Dmg): SystemState {
        val cart = parseCartridge(rom).fold({ error("test ROM failed to parse: $it") }, { it })
        return postBootState(cart, mode)
    }

    /**
     * Runs a Mooneye test ROM until it executes its `LD B,B` breakpoint, then
     * checks the Fibonacci register fingerprint (B=3,C=5,D=8,E=13,H=21,L=34).
     */
    fun runMooneye(rom: ByteArray, mode: HwMode = HwMode.Dmg, maxEmulatedSeconds: Int = 30): String {
        var s = boot(rom, mode)
        val limit = maxEmulatedSeconds * CYCLES_PER_SECOND
        while (s.tCycles < limit) {
            val c = s.cpu
            if (!c.halted && !c.stopped && !c.locked && peek(s, c.pc) == 0x40) {
                val fib = c.b == 3 && c.c == 5 && c.d == 8 && c.e == 13 && c.h == 21 && c.l == 34
                return if (fib) "passed" else "failed: b=${c.b} c=${c.c} d=${c.d} e=${c.e} h=${c.h} l=${c.l}"
            }
            s = stepInstruction(s)
        }
        return "timed out at pc=${"%04X".format(s.cpu.pc)} halted=${s.cpu.halted} stopped=${s.cpu.stopped}"
    }

    /**
     * Runs a Blargg test ROM that reports through cart RAM: status at 0xA000
     * (0x80 = running, 0 = pass), signature DE B0 61 at 0xA001-3, text after.
     */
    fun runBlarggMemory(rom: ByteArray, maxEmulatedSeconds: Int = 120): String {
        var s = boot(rom)
        val limit = maxEmulatedSeconds * CYCLES_PER_SECOND
        var steps = 0
        while (s.tCycles < limit) {
            s = stepInstruction(s)
            if (steps++ % 0x20000 == 0 && blarggMemoryDone(s)) break
        }
        if (!blarggMemoryDone(s)) return "timed out (status=${s.cart.ram[0].toInt() and 0xFF})"
        val status = s.cart.ram[0].toInt() and 0xFF
        val text = buildString {
            var i = 4
            while (i < s.cart.ram.size) {
                val b = s.cart.ram[i++].toInt() and 0xFF
                if (b == 0) break
                append(b.toChar())
            }
        }
        return if (status == 0) "Passed\n$text" else "Failed status=$status\n$text"
    }

    private fun blarggMemoryDone(s: SystemState): Boolean =
        s.cart.ram.size >= 4 &&
            (s.cart.ram[1].toInt() and 0xFF) == 0xDE &&
            (s.cart.ram[2].toInt() and 0xFF) == 0xB0 &&
            (s.cart.ram[3].toInt() and 0xFF) == 0x61 &&
            (s.cart.ram[0].toInt() and 0xFF) != 0x80

    /**
     * Runs a Blargg test ROM that reports through the link port, until the
     * output contains a verdict or [maxEmulatedSeconds] of machine time pass.
     */
    fun runBlarggSerial(rom: ByteArray, maxEmulatedSeconds: Int = 120): String {
        var s = boot(rom)
        val out = StringBuilder()
        val ports = Ports(serial = SerialOut { b -> out.append(b.toChar()) })
        val limit = maxEmulatedSeconds * CYCLES_PER_SECOND
        var steps = 0
        while (s.tCycles < limit) {
            s = stepInstruction(s, ports)
            if (steps++ % 0x10000 == 0) {
                val text = out.toString()
                if ("Passed" in text || "Failed" in text) break
            }
        }
        return out.toString()
    }
}
