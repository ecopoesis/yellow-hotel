package gbc.core.cpu

import gbc.fixtures.RecordingBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun busWith(vararg code: Int, at: Int = 0x100): RecordingBus {
    val bus = RecordingBus()
    code.forEachIndexed { i, byte -> bus.mem[at + i] = byte.toByte() }
    return bus
}

class Sm83Spec : FunSpec({

    test("interrupt dispatch takes 5 M-cycles, pushes PC, clears IF and IME, jumps to the vector") {
        val bus = busWith(0x00)
        bus.interruptEnable = 0x01
        bus.raiseInterrupt(0x01)
        val cpu = stepCpu(CpuState(pc = 0x1234, sp = 0xC100, ime = true), bus)

        cpu.pc shouldBe 0x0040
        cpu.sp shouldBe 0xC0FE
        cpu.ime shouldBe false
        (bus.mem[0xC0FF].toInt() and 0xFF) shouldBe 0x12
        (bus.mem[0xC0FE].toInt() and 0xFF) shouldBe 0x34
        bus.cycles.size shouldBe 5
        bus.cycles.map { it.kind } shouldBe listOf("---", "---", "-wm", "-wm", "---")
        bus.pendingInterrupts() shouldBe 0 // IF bit acknowledged
    }

    test("dispatch priority: the lowest pending bit (VBlank) wins") {
        val bus = busWith(0x00)
        bus.interruptEnable = 0x1F
        bus.raiseInterrupt(0x12) // STAT (bit 1) + Joypad (bit 4)
        stepCpu(CpuState(pc = 0x1234, sp = 0xC100, ime = true), bus).pc shouldBe 0x0048
    }

    test("IE-push cancellation: a PCH push that clears IE sends the CPU to 0x0000 and keeps IF") {
        val bus = busWith(0x00)
        bus.interruptEnable = 0x01
        bus.raiseInterrupt(0x01)
        // SP=0x0000 makes the PCH push land on IE (0xFFFF); PCH of 0x0012 is 0x00, wiping IE.
        val cpu = stepCpu(CpuState(pc = 0x0012, sp = 0x0000, ime = true), bus)

        cpu.pc shouldBe 0x0000
        cpu.ime shouldBe false
        bus.interruptEnable = 0x01 // restore IE: IF must still be set (never acknowledged)
        bus.pendingInterrupts() shouldBe 0x01
    }

    test("EI enables IME only after the following instruction") {
        val bus = busWith(0xFB, 0x00) // EI; NOP
        var cpu = stepCpu(CpuState(pc = 0x100), bus)
        cpu.ime shouldBe false
        cpu.eiPending shouldBe true
        cpu = stepCpu(cpu, bus)
        cpu.ime shouldBe true
        cpu.eiPending shouldBe false
    }

    test("EI immediately followed by DI never enables IME") {
        val bus = busWith(0xFB, 0xF3) // EI; DI
        var cpu = stepCpu(CpuState(pc = 0x100), bus)
        cpu = stepCpu(cpu, bus)
        cpu.ime shouldBe false
        cpu.eiPending shouldBe false
    }

    test("RETI enables IME immediately") {
        val bus = busWith(0xD9) // RETI
        bus.mem[0xC000] = 0x00.toByte()
        bus.mem[0xC001] = 0x80.toByte()
        val cpu = stepCpu(CpuState(pc = 0x100, sp = 0xC000), bus)
        cpu.ime shouldBe true
        cpu.pc shouldBe 0x8000
    }

    test("halt bug: HALT with IME=0 and a pending interrupt executes the next byte twice") {
        val bus = busWith(0x76, 0x3C) // HALT; INC A
        bus.interruptEnable = 0x01
        bus.raiseInterrupt(0x01)
        var cpu = stepCpu(CpuState(pc = 0x100), bus)
        cpu.halted shouldBe false
        cpu.haltBug shouldBe true
        cpu = stepCpu(cpu, bus) // INC A, PC not incremented
        cpu = stepCpu(cpu, bus) // INC A again
        cpu.a shouldBe 2
        cpu.pc shouldBe 0x102
    }

    test("HALT with IME=0 and nothing pending sleeps until a request, then continues without dispatch") {
        val bus = busWith(0x76, 0x3C) // HALT; INC A
        bus.interruptEnable = 0x01
        var cpu = stepCpu(CpuState(pc = 0x100), bus)
        cpu.halted shouldBe true
        cpu = stepCpu(cpu, bus)
        cpu.halted shouldBe true // still asleep
        bus.raiseInterrupt(0x01)
        cpu = stepCpu(cpu, bus)
        cpu.halted shouldBe false
        cpu.pc shouldBe 0x101 // woke past HALT, no dispatch
        cpu = stepCpu(cpu, bus)
        cpu.a shouldBe 1
    }

    test("HALT with IME=1 wakes directly into dispatch") {
        val bus = busWith(0x76)
        bus.interruptEnable = 0x01
        var cpu = stepCpu(CpuState(pc = 0x100, sp = 0xC100, ime = true), bus)
        cpu.halted shouldBe true
        bus.raiseInterrupt(0x01)
        cpu = stepCpu(cpu, bus)
        cpu.halted shouldBe false
        cpu.pc shouldBe 0x0040
        cpu.ime shouldBe false
    }

    test("hardware-invalid opcodes wedge the CPU permanently") {
        for (op in listOf(0xD3, 0xDB, 0xDD, 0xE3, 0xE4, 0xEB, 0xEC, 0xED, 0xF4, 0xFC, 0xFD)) {
            val bus = busWith(op)
            var cpu = stepCpu(CpuState(pc = 0x100), bus)
            cpu.locked shouldBe true
            bus.interruptEnable = 0x1F
            bus.raiseInterrupt(0x1F)
            val before = cpu
            cpu = stepCpu(cpu.copy(ime = true), bus) // even interrupts can't revive it
            cpu.pc shouldBe before.pc
            cpu.locked shouldBe true
        }
    }

    test("a stopped CPU burns idle cycles without changing state") {
        val bus = busWith(0x00)
        val stopped = CpuState(pc = 0x100, stopped = true)
        stepCpu(stopped, bus) shouldBe stopped
        bus.cycles.single().kind shouldBe "---"
    }
})
