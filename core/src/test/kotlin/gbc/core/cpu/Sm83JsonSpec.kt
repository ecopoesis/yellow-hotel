package gbc.core.cpu

import gbc.fixtures.Accuracy
import gbc.fixtures.RecordingBus
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runs the SingleStepTests/sm83 v1 suite: 1000 cases per opcode verifying
 * final register state, final RAM contents, and the cycle-by-cycle bus trace.
 */
class Sm83JsonSpec : FunSpec({
    tags(Accuracy)

    val dir = System.getProperty("sm83.dir")?.let(::File)
    if (dir == null || !dir.isDirectory) {
        test("sm83 suite present") {
            fail("sm83.dir not set or missing; run ./gradlew :core:downloadSm83Tests (got: $dir)")
        }
    }

    val json = Json { ignoreUnknownKeys = true }
    val filter = System.getProperty("sm83.filter")?.split(",")?.toSet()
    val files = dir?.takeIf { it.isDirectory }
        ?.listFiles { f -> f.name.endsWith(".json") }
        ?.filter { filter == null || it.name.removeSuffix(".json") in filter }
        ?.sortedBy { it.name }
        .orEmpty()

    for (file in files) {
        test("opcode ${file.name.removeSuffix(".json")}") {
            val cases = json.parseToJsonElement(file.readText()).jsonArray
            for (case in cases) {
                val obj = case.jsonObject
                val name = obj["name"]!!.jsonPrimitive.content
                val initial = obj["initial"]!!.jsonObject
                val final = obj["final"]!!.jsonObject
                val expectedCycles = obj["cycles"]!!.jsonArray

                val bus = RecordingBus()
                for (pair in initial["ram"]!!.jsonArray) {
                    val (addr, value) = pair.jsonArray.map { it.jsonPrimitive.int }
                    bus.mem[addr] = value.toByte()
                }
                var cpu = CpuState(
                    a = initial["a"]!!.jsonPrimitive.int,
                    f = initial["f"]!!.jsonPrimitive.int,
                    b = initial["b"]!!.jsonPrimitive.int,
                    c = initial["c"]!!.jsonPrimitive.int,
                    d = initial["d"]!!.jsonPrimitive.int,
                    e = initial["e"]!!.jsonPrimitive.int,
                    h = initial["h"]!!.jsonPrimitive.int,
                    l = initial["l"]!!.jsonPrimitive.int,
                    sp = initial["sp"]!!.jsonPrimitive.int,
                    pc = initial["pc"]!!.jsonPrimitive.int,
                    ime = initial["ime"]!!.jsonPrimitive.int == 1,
                    eiPending = initial["ei"]?.jsonPrimitive?.int == 1,
                )

                // HALT/STOP cases span several steps (fetch + idle cycles); everything
                // else completes in one. Guard against runaway mismatch.
                var steps = 0
                while (bus.cycles.size < expectedCycles.size && steps++ < 8) {
                    cpu = stepCpu(cpu, bus)
                }

                fun mismatch(what: String): Nothing = fail(
                    "case '$name' ($what)\n" +
                        "  cpu:     $cpu\n" +
                        "  cycles:  ${bus.cycles}\n" +
                        "  expected:$expectedCycles",
                )

                val actualRegs = listOf(
                    cpu.a, cpu.f, cpu.b, cpu.c, cpu.d, cpu.e, cpu.h, cpu.l, cpu.sp, cpu.pc,
                    if (cpu.ime) 1 else 0, if (cpu.eiPending) 1 else 0,
                )
                val wantRegs = listOf(
                    final["a"]!!.jsonPrimitive.int, final["f"]!!.jsonPrimitive.int,
                    final["b"]!!.jsonPrimitive.int, final["c"]!!.jsonPrimitive.int,
                    final["d"]!!.jsonPrimitive.int, final["e"]!!.jsonPrimitive.int,
                    final["h"]!!.jsonPrimitive.int, final["l"]!!.jsonPrimitive.int,
                    final["sp"]!!.jsonPrimitive.int, final["pc"]!!.jsonPrimitive.int,
                    final["ime"]!!.jsonPrimitive.int, final["ei"]?.jsonPrimitive?.int ?: 0,
                )
                if (actualRegs != wantRegs) mismatch("registers a,f,b,c,d,e,h,l,sp,pc,ime,ei: want $wantRegs got $actualRegs")

                for (pair in final["ram"]!!.jsonArray) {
                    val (addr, value) = pair.jsonArray.map { it.jsonPrimitive.int }
                    if ((bus.mem[addr].toInt() and 0xFF) != value) {
                        mismatch("ram[$addr] want $value got ${bus.mem[addr].toInt() and 0xFF}")
                    }
                }

                if (bus.cycles.size != expectedCycles.size) mismatch("cycle count")
                for ((i, expected) in expectedCycles.withIndex()) {
                    val e = expected.jsonArray
                    val actual = bus.cycles[i]
                    if (actual.addr != e[0].jsonPrimitive.int ||
                        actual.data != e[1].jsonPrimitive.int ||
                        actual.kind != e[2].jsonPrimitive.content
                    ) {
                        mismatch("cycle $i")
                    }
                }
            }
        }
    }
})
