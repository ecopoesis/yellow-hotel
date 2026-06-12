package gbc.core.apu

/**
 * The four-channel APU. The frame sequencer (length/envelope/sweep clocks) is
 * driven by falling edges of a DIV bit (bit 12 of the system counter, bit 13
 * in double speed) — the "DIV-APU" coupling that makes DIV writes audible.
 * Sample generation: the mixer output is box-filtered down to 48 kHz into a
 * ring buffer owned by the state; the host drains it via [takeSamples].
 */
data class Square(
    val enabled: Boolean = false,
    val dacOn: Boolean = false,
    val duty: Int = 0,
    val dutyPos: Int = 0,
    val length: Int = 0,
    val lengthEnable: Boolean = false,
    val freq: Int = 0, // 11 bits
    val freqTimer: Int = 0,
    val volume: Int = 0,
    val envStart: Int = 0,
    val envUp: Boolean = false,
    val envPeriod: Int = 0,
    val envTimer: Int = 0,
    // sweep (channel 1 only)
    val sweepPeriod: Int = 0,
    val sweepNegate: Boolean = false,
    val sweepShift: Int = 0,
    val sweepEnabled: Boolean = false,
    val sweepShadow: Int = 0,
    val sweepTimer: Int = 0,
    val sweepNegateUsed: Boolean = false,
)

data class Wave(
    val enabled: Boolean = false,
    val dacOn: Boolean = false,
    val length: Int = 0, // 0..256
    val lengthEnable: Boolean = false,
    val volumeCode: Int = 0,
    val freq: Int = 0,
    val freqTimer: Int = 0,
    val position: Int = 0,
    val sampleBuffer: Int = 0,
    val justAccessed: Boolean = false, // wave RAM readable on DMG only right at a fetch
)

data class Noise(
    val enabled: Boolean = false,
    val dacOn: Boolean = false,
    val length: Int = 0,
    val lengthEnable: Boolean = false,
    val volume: Int = 0,
    val envStart: Int = 0,
    val envUp: Boolean = false,
    val envPeriod: Int = 0,
    val envTimer: Int = 0,
    val clockShift: Int = 0,
    val widthMode: Boolean = false,
    val divisorCode: Int = 0,
    val freqTimer: Int = 0,
    val lfsr: Int = 0x7FFF,
)

const val SAMPLE_RATE = 48_000
private const val CPU_HZ = 4_194_304
private const val RING_SIZE = 32 * 1024 // stereo frames

data class ApuState(
    val dmgMode: Boolean = false,
    val enabled: Boolean = true, // NR52 bit 7; boot ROM leaves sound on
    val frameStep: Int = 0,
    val lastDivBit: Boolean = false,
    val ch1: Square = Square(),
    val ch2: Square = Square(),
    val ch3: Wave = Wave(),
    val ch4: Noise = Noise(),
    val nr50: Int = 0x77,
    val nr51: Int = 0xF3,
    val waveRam: ByteArray = ByteArray(16),
    val samples: FloatArray = FloatArray(RING_SIZE * 2),
    val sampleHead: Int = 0, // next write (stereo frame index)
    val sampleCount: Int = 0,
    val sampleClock: Int = 0, // fixed-point downsample accumulator
    val accL: Float = 0f,
    val accR: Float = 0f,
    val accN: Int = 0,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Copies up to [max] pending stereo frames out of the ring; returns the count taken. */
fun takeSamples(s: ApuState, out: FloatArray, max: Int): Pair<ApuState, Int> {
    val take = minOf(s.sampleCount, max)
    var tail = (s.sampleHead - s.sampleCount + RING_SIZE) % RING_SIZE
    for (i in 0 until take) {
        out[i * 2] = s.samples[tail * 2]
        out[i * 2 + 1] = s.samples[tail * 2 + 1]
        tail = (tail + 1) % RING_SIZE
    }
    return s.copy(sampleCount = s.sampleCount - take) to take
}

private val DUTY = intArrayOf(0b00000001, 0b00000011, 0b00001111, 0b11111100)
private val NOISE_DIVISOR = intArrayOf(8, 16, 32, 48, 64, 80, 96, 112)

/** Register read OR-masks: unused/write-only bits read as 1. */
private val READ_MASK = intArrayOf(
    0x80, 0x3F, 0x00, 0xFF, 0xBF, // NR10-14
    0xFF, 0x3F, 0x00, 0xFF, 0xBF, // NR20-24 (NR20 unused)
    0x7F, 0xFF, 0x9F, 0xFF, 0xBF, // NR30-34
    0xFF, 0xFF, 0x00, 0x00, 0xBF, // NR40-44 (NR40 unused)
    0x00, 0x00, 0x70, // NR50-52
)

class ApuTick(val apu: ApuState)

fun apuTick(a: ApuState, sysCounter: Int, doubleSpeed: Boolean, tCycles: Int): ApuTick {
    val run = ApuRun(a)
    val divBit = (sysCounter shr (if (doubleSpeed) 13 else 12)) and 1
    run.tick(divBit == 1, tCycles)
    return ApuTick(run.toState())
}

fun apuRead(a: ApuState, reg: Int): Int {
    val raw = when (reg) {
        0x10 -> (a.ch1.sweepPeriod shl 4) or (if (a.ch1.sweepNegate) 8 else 0) or a.ch1.sweepShift
        0x11 -> a.ch1.duty shl 6
        0x12 -> (a.ch1.envStart shl 4) or (if (a.ch1.envUp) 8 else 0) or a.ch1.envPeriod
        0x13 -> 0
        0x14 -> if (a.ch1.lengthEnable) 0x40 else 0
        0x16 -> a.ch2.duty shl 6
        0x17 -> (a.ch2.envStart shl 4) or (if (a.ch2.envUp) 8 else 0) or a.ch2.envPeriod
        0x18 -> 0
        0x19 -> if (a.ch2.lengthEnable) 0x40 else 0
        0x1A -> if (a.ch3.dacOn) 0x80 else 0
        0x1B -> 0
        0x1C -> a.ch3.volumeCode shl 5
        0x1D -> 0
        0x1E -> if (a.ch3.lengthEnable) 0x40 else 0
        0x20 -> 0
        0x21 -> (a.ch4.envStart shl 4) or (if (a.ch4.envUp) 8 else 0) or a.ch4.envPeriod
        0x22 -> (a.ch4.clockShift shl 4) or (if (a.ch4.widthMode) 8 else 0) or a.ch4.divisorCode
        0x23 -> if (a.ch4.lengthEnable) 0x40 else 0
        0x24 -> a.nr50
        0x25 -> a.nr51
        0x26 -> (if (a.enabled) 0x80 else 0) or
            (if (a.ch1.enabled) 1 else 0) or (if (a.ch2.enabled) 2 else 0) or
            (if (a.ch3.enabled) 4 else 0) or (if (a.ch4.enabled) 8 else 0)
        else -> 0
    }
    val mask = when (reg) {
        in 0x10..0x14 -> READ_MASK[reg - 0x10]
        in 0x16..0x19 -> READ_MASK[reg - 0x15 + 5] // NR21 at index 6
        in 0x1A..0x1E -> READ_MASK[reg - 0x1A + 10]
        in 0x20..0x23 -> READ_MASK[reg - 0x20 + 15 + 1] // NR41 at index 16
        0x24, 0x25, 0x26 -> READ_MASK[reg - 0x24 + 20]
        else -> 0xFF // 0x15, 0x1F: no register there
    }
    return raw or mask
}

/** Wave RAM: freely accessible when CH3 is off; while playing, CGB reads the current byte. */
fun waveRamRead(a: ApuState, index: Int): Int = when {
    !a.ch3.enabled -> a.waveRam[index].toInt() and 0xFF
    a.dmgMode && !a.ch3.justAccessed -> 0xFF
    else -> a.waveRam[a.ch3.position / 2].toInt() and 0xFF
}

fun waveRamWrite(a: ApuState, index: Int, value: Int): ApuState {
    when {
        !a.ch3.enabled -> a.waveRam[index] = value.toByte()
        a.dmgMode && !a.ch3.justAccessed -> {} // inaccessible: write lost
        else -> a.waveRam[a.ch3.position / 2] = value.toByte()
    }
    return a
}

@Suppress("CyclomaticComplexMethod")
fun apuWrite(a: ApuState, reg: Int, value: Int): ApuState {
    if (!a.enabled && reg != 0x26) {
        // Power off: register writes ignored — except DMG keeps length counters writable
        return if (a.dmgMode) {
            when (reg) {
                0x11 -> a.copy(ch1 = a.ch1.copy(length = 64 - (value and 0x3F)))
                0x16 -> a.copy(ch2 = a.ch2.copy(length = 64 - (value and 0x3F)))
                0x1B -> a.copy(ch3 = a.ch3.copy(length = 256 - value))
                0x20 -> a.copy(ch4 = a.ch4.copy(length = 64 - (value and 0x3F)))
                else -> a
            }
        } else {
            a
        }
    }
    return when (reg) {
        0x10 -> {
            var c = a.ch1.copy(
                sweepPeriod = (value shr 4) and 7,
                sweepNegate = value and 8 != 0,
                sweepShift = value and 7,
            )
            // Clearing negate after a negate-mode calculation kills the channel
            if (a.ch1.sweepNegateUsed && a.ch1.sweepNegate && !c.sweepNegate) c = c.copy(enabled = false)
            a.copy(ch1 = c)
        }
        0x11 -> a.copy(ch1 = a.ch1.copy(duty = (value shr 6) and 3, length = 64 - (value and 0x3F)))
        0x12 -> a.copy(
            ch1 = a.ch1.copy(
                envStart = (value shr 4) and 0xF,
                envUp = value and 8 != 0,
                envPeriod = value and 7,
                dacOn = value and 0xF8 != 0,
                enabled = a.ch1.enabled && value and 0xF8 != 0,
            ),
        )
        0x13 -> a.copy(ch1 = a.ch1.copy(freq = (a.ch1.freq and 0x700) or value))
        0x14 -> trigger1(a, value)
        0x16 -> a.copy(ch2 = a.ch2.copy(duty = (value shr 6) and 3, length = 64 - (value and 0x3F)))
        0x17 -> a.copy(
            ch2 = a.ch2.copy(
                envStart = (value shr 4) and 0xF,
                envUp = value and 8 != 0,
                envPeriod = value and 7,
                dacOn = value and 0xF8 != 0,
                enabled = a.ch2.enabled && value and 0xF8 != 0,
            ),
        )
        0x18 -> a.copy(ch2 = a.ch2.copy(freq = (a.ch2.freq and 0x700) or value))
        0x19 -> trigger2(a, value)
        0x1A -> a.copy(ch3 = a.ch3.copy(dacOn = value and 0x80 != 0, enabled = a.ch3.enabled && value and 0x80 != 0))
        0x1B -> a.copy(ch3 = a.ch3.copy(length = 256 - value))
        0x1C -> a.copy(ch3 = a.ch3.copy(volumeCode = (value shr 5) and 3))
        0x1D -> a.copy(ch3 = a.ch3.copy(freq = (a.ch3.freq and 0x700) or value))
        0x1E -> trigger3(a, value)
        0x20 -> a.copy(ch4 = a.ch4.copy(length = 64 - (value and 0x3F)))
        0x21 -> a.copy(
            ch4 = a.ch4.copy(
                envStart = (value shr 4) and 0xF,
                envUp = value and 8 != 0,
                envPeriod = value and 7,
                dacOn = value and 0xF8 != 0,
                enabled = a.ch4.enabled && value and 0xF8 != 0,
            ),
        )
        0x22 -> a.copy(
            ch4 = a.ch4.copy(
                clockShift = (value shr 4) and 0xF,
                widthMode = value and 8 != 0,
                divisorCode = value and 7,
            ),
        )
        0x23 -> trigger4(a, value)
        0x24 -> a.copy(nr50 = value)
        0x25 -> a.copy(nr51 = value)
        0x26 -> power(a, value)
        else -> a
    }
}

private fun power(a: ApuState, value: Int): ApuState {
    val on = value and 0x80 != 0
    if (on == a.enabled) return a
    return if (on) {
        a.copy(enabled = true, frameStep = 0)
    } else {
        // Power off zeroes every register; DMG preserves the length counters
        ApuState(
            dmgMode = a.dmgMode,
            enabled = false,
            ch1 = Square(length = if (a.dmgMode) a.ch1.length else 0),
            ch2 = Square(length = if (a.dmgMode) a.ch2.length else 0),
            ch3 = Wave(length = if (a.dmgMode) a.ch3.length else 0),
            ch4 = Noise(length = if (a.dmgMode) a.ch4.length else 0),
            nr50 = 0,
            nr51 = 0,
            waveRam = a.waveRam, // wave RAM survives power cycles
            samples = a.samples,
            sampleHead = a.sampleHead,
            sampleCount = a.sampleCount,
            sampleClock = a.sampleClock,
            accL = a.accL,
            accR = a.accR,
            accN = a.accN,
        )
    }
}

/**
 * Enabling length in the first half of the length period clocks it once
 * (the extra-clock quirk); a trigger that reloads length while enabled in the
 * first half also clocks the reload.
 */
private fun lengthQuirk(frameStep: Int): Boolean = frameStep and 1 == 1

private fun applyLengthEnable(
    oldEnable: Boolean,
    newEnable: Boolean,
    length: Int,
    frameStep: Int,
    enabled: Boolean,
): Triple<Int, Boolean, Boolean> {
    var len = length
    var on = enabled
    if (!oldEnable && newEnable && lengthQuirk(frameStep) && len > 0) {
        len--
        if (len == 0) on = false
    }
    return Triple(len, newEnable, on)
}

private fun sweepCalc(c: Square): Pair<Int, Boolean> {
    val delta = c.sweepShadow shr c.sweepShift
    val next = if (c.sweepNegate) c.sweepShadow - delta else c.sweepShadow + delta
    return next to (next > 2047)
}

private fun trigger1(a: ApuState, value: Int): ApuState {
    var c = a.ch1.copy(freq = (a.ch1.freq and 0xFF) or ((value and 7) shl 8))
    val (len0, en, on0) = applyLengthEnable(c.lengthEnable, value and 0x40 != 0, c.length, a.frameStep, c.enabled)
    c = c.copy(length = len0, lengthEnable = en, enabled = on0)
    if (value and 0x80 != 0) {
        var len = c.length
        if (len == 0) {
            len = 64
            if (en && lengthQuirk(a.frameStep)) len--
        }
        c = c.copy(
            enabled = c.dacOn,
            length = len,
            freqTimer = (2048 - c.freq) * 4,
            volume = c.envStart,
            envTimer = if (c.envPeriod == 0) 8 else c.envPeriod,
            sweepShadow = c.freq,
            sweepTimer = if (c.sweepPeriod == 0) 8 else c.sweepPeriod,
            sweepEnabled = c.sweepPeriod != 0 || c.sweepShift != 0,
            sweepNegateUsed = false,
        )
        if (c.sweepShift != 0) {
            val (_, overflow) = sweepCalc(c)
            c = c.copy(sweepNegateUsed = c.sweepNegate)
            if (overflow) c = c.copy(enabled = false)
        }
    }
    return a.copy(ch1 = c)
}

private fun trigger2(a: ApuState, value: Int): ApuState {
    var c = a.ch2.copy(freq = (a.ch2.freq and 0xFF) or ((value and 7) shl 8))
    val (len0, en, on0) = applyLengthEnable(c.lengthEnable, value and 0x40 != 0, c.length, a.frameStep, c.enabled)
    c = c.copy(length = len0, lengthEnable = en, enabled = on0)
    if (value and 0x80 != 0) {
        var len = c.length
        if (len == 0) {
            len = 64
            if (en && lengthQuirk(a.frameStep)) len--
        }
        c = c.copy(
            enabled = c.dacOn,
            length = len,
            freqTimer = (2048 - c.freq) * 4,
            volume = c.envStart,
            envTimer = if (c.envPeriod == 0) 8 else c.envPeriod,
        )
    }
    return a.copy(ch2 = c)
}

private fun trigger3(a: ApuState, value: Int): ApuState {
    var c = a.ch3.copy(freq = (a.ch3.freq and 0xFF) or ((value and 7) shl 8))
    val (len0, en, on0) = applyLengthEnable(c.lengthEnable, value and 0x40 != 0, c.length, a.frameStep, c.enabled)
    c = c.copy(length = len0, lengthEnable = en, enabled = on0)
    if (value and 0x80 != 0) {
        // DMG: triggering while the wave channel reads its sample corrupts wave RAM
        if (a.dmgMode && c.enabled && c.freqTimer <= 2) {
            corruptWaveRam(a.waveRam, c.position)
        }
        var len = c.length
        if (len == 0) {
            len = 256
            if (en && lengthQuirk(a.frameStep)) len--
        }
        c = c.copy(
            enabled = c.dacOn,
            length = len,
            freqTimer = (2048 - c.freq) * 2 + 6, // trigger delay before the first fetch
            position = 0,
        )
    }
    return a.copy(ch3 = c)
}

private fun corruptWaveRam(waveRam: ByteArray, position: Int) {
    val next = ((position + 1) % 32) / 2
    if (next < 4) {
        waveRam[0] = waveRam[next]
    } else {
        val base = (next / 4) * 4
        for (i in 0 until 4) waveRam[i] = waveRam[base + i]
    }
}

private fun trigger4(a: ApuState, value: Int): ApuState {
    var c = a.ch4
    val (len0, en, on0) = applyLengthEnable(c.lengthEnable, value and 0x40 != 0, c.length, a.frameStep, c.enabled)
    c = c.copy(length = len0, lengthEnable = en, enabled = on0)
    if (value and 0x80 != 0) {
        var len = c.length
        if (len == 0) {
            len = 64
            if (en && lengthQuirk(a.frameStep)) len--
        }
        c = c.copy(
            enabled = c.dacOn,
            length = len,
            freqTimer = NOISE_DIVISOR[c.divisorCode] shl c.clockShift,
            volume = c.envStart,
            envTimer = if (c.envPeriod == 0) 8 else c.envPeriod,
            lfsr = 0x7FFF,
        )
    }
    return a.copy(ch4 = c)
}

@Suppress("TooManyFunctions")
private class ApuRun(s: ApuState) {
    private val dmgMode = s.dmgMode
    private val enabled = s.enabled
    private var frameStep = s.frameStep
    private var lastDivBit = s.lastDivBit
    private var ch1 = s.ch1
    private var ch2 = s.ch2
    private var ch3 = s.ch3
    private var ch4 = s.ch4
    private val nr50 = s.nr50
    private val nr51 = s.nr51
    private val waveRam = s.waveRam
    private val samples = s.samples
    private var sampleHead = s.sampleHead
    private var sampleCount = s.sampleCount
    private var sampleClock = s.sampleClock
    private var accL = s.accL
    private var accR = s.accR
    private var accN = s.accN

    fun toState() = ApuState(
        dmgMode, enabled, frameStep, lastDivBit, ch1, ch2, ch3, ch4, nr50, nr51,
        waveRam, samples, sampleHead, sampleCount, sampleClock, accL, accR, accN,
    )

    fun tick(divBit: Boolean, tCycles: Int) {
        if (enabled) {
            if (lastDivBit && !divBit) frameSequencer()
            lastDivBit = divBit
            repeat(tCycles) { tickChannels() }
        }
        sample(tCycles)
    }

    private fun frameSequencer() {
        when (frameStep) {
            0, 4 -> clockLengths()
            2, 6 -> {
                clockLengths()
                clockSweep()
            }
            7 -> clockEnvelopes()
        }
        frameStep = (frameStep + 1) and 7
    }

    private fun clockLengths() {
        if (ch1.lengthEnable && ch1.length > 0) {
            ch1 = ch1.copy(length = ch1.length - 1, enabled = ch1.enabled && ch1.length - 1 > 0)
        }
        if (ch2.lengthEnable && ch2.length > 0) {
            ch2 = ch2.copy(length = ch2.length - 1, enabled = ch2.enabled && ch2.length - 1 > 0)
        }
        if (ch3.lengthEnable && ch3.length > 0) {
            ch3 = ch3.copy(length = ch3.length - 1, enabled = ch3.enabled && ch3.length - 1 > 0)
        }
        if (ch4.lengthEnable && ch4.length > 0) {
            ch4 = ch4.copy(length = ch4.length - 1, enabled = ch4.enabled && ch4.length - 1 > 0)
        }
    }

    private fun clockSweep() {
        var c = ch1
        if (c.sweepTimer > 0) c = c.copy(sweepTimer = c.sweepTimer - 1)
        if (c.sweepTimer == 0) {
            c = c.copy(sweepTimer = if (c.sweepPeriod == 0) 8 else c.sweepPeriod)
            if (c.sweepEnabled && c.sweepPeriod != 0) {
                val (next, overflow) = sweepCalc(c)
                c = c.copy(sweepNegateUsed = c.sweepNegate || c.sweepNegateUsed)
                if (overflow) {
                    c = c.copy(enabled = false)
                } else if (c.sweepShift != 0) {
                    c = c.copy(sweepShadow = next, freq = next)
                    val (_, overflow2) = sweepCalc(c)
                    if (overflow2) c = c.copy(enabled = false)
                }
            }
        }
        ch1 = c
    }

    private fun clockEnvelopes() {
        ch1 = envelope(ch1)
        ch2 = envelope(ch2)
        var c = ch4
        if (c.envPeriod != 0) {
            var t = c.envTimer - 1
            if (t == 0) {
                t = c.envPeriod
                val v = c.volume + if (c.envUp) 1 else -1
                if (v in 0..15) c = c.copy(volume = v)
            }
            c = c.copy(envTimer = t)
        }
        ch4 = c
    }

    private fun envelope(c: Square): Square {
        if (c.envPeriod == 0) return c
        var t = c.envTimer - 1
        var out = c
        if (t == 0) {
            t = c.envPeriod
            val v = c.volume + if (c.envUp) 1 else -1
            if (v in 0..15) out = out.copy(volume = v)
        }
        return out.copy(envTimer = t)
    }

    private fun tickChannels() {
        if (ch1.enabled) {
            var t = ch1.freqTimer - 1
            if (t <= 0) {
                t = (2048 - ch1.freq) * 4
                ch1 = ch1.copy(dutyPos = (ch1.dutyPos + 1) and 7)
            }
            ch1 = ch1.copy(freqTimer = t)
        }
        if (ch2.enabled) {
            var t = ch2.freqTimer - 1
            if (t <= 0) {
                t = (2048 - ch2.freq) * 4
                ch2 = ch2.copy(dutyPos = (ch2.dutyPos + 1) and 7)
            }
            ch2 = ch2.copy(freqTimer = t)
        }
        if (ch3.enabled) {
            var t = ch3.freqTimer - 1
            var justAccessed = false
            if (t <= 0) {
                t = (2048 - ch3.freq) * 2
                val pos = (ch3.position + 1) % 32
                val byte = waveRam[pos / 2].toInt() and 0xFF
                val nibble = if (pos and 1 == 0) byte shr 4 else byte and 0xF
                ch3 = ch3.copy(position = pos, sampleBuffer = nibble)
                justAccessed = true
            }
            ch3 = ch3.copy(freqTimer = t, justAccessed = justAccessed)
        }
        if (ch4.enabled) {
            var t = ch4.freqTimer - 1
            if (t <= 0) {
                t = NOISE_DIVISOR[ch4.divisorCode] shl ch4.clockShift
                var lfsr = ch4.lfsr
                val xor = (lfsr and 1) xor ((lfsr shr 1) and 1)
                lfsr = (lfsr shr 1) or (xor shl 14)
                if (ch4.widthMode) lfsr = (lfsr and 0x40.inv()) or (xor shl 6)
                ch4 = ch4.copy(lfsr = lfsr)
            }
            ch4 = ch4.copy(freqTimer = t)
        }
    }

    private fun channelOutputs(): IntArray {
        val o1 = if (ch1.enabled && ch1.dacOn && (DUTY[ch1.duty] shr ch1.dutyPos) and 1 == 1) ch1.volume else 0
        val o2 = if (ch2.enabled && ch2.dacOn && (DUTY[ch2.duty] shr ch2.dutyPos) and 1 == 1) ch2.volume else 0
        val o3 = if (ch3.enabled && ch3.dacOn && ch3.volumeCode != 0) ch3.sampleBuffer shr (ch3.volumeCode - 1) else 0
        val o4 = if (ch4.enabled && ch4.dacOn && ch4.lfsr and 1 == 0) ch4.volume else 0
        return intArrayOf(o1, o2, o3, o4)
    }

    private fun sample(tCycles: Int) {
        val out = channelOutputs()
        var left = 0f
        var right = 0f
        for (ch in 0..3) {
            val v = out[ch] / 15f
            if (nr51 and (1 shl (ch + 4)) != 0) left += v
            if (nr51 and (1 shl ch) != 0) right += v
        }
        left = left / 4f * (((nr50 shr 4) and 7) + 1) / 8f
        right = right / 4f * ((nr50 and 7) + 1) / 8f
        accL += left * tCycles
        accR += right * tCycles
        accN += tCycles
        sampleClock += SAMPLE_RATE * tCycles
        if (sampleClock >= CPU_HZ) {
            sampleClock -= CPU_HZ
            if (sampleCount < RING_SIZE) {
                samples[sampleHead * 2] = accL / accN
                samples[sampleHead * 2 + 1] = accR / accN
                sampleHead = (sampleHead + 1) % RING_SIZE
                sampleCount++
            }
            accL = 0f
            accR = 0f
            accN = 0
        }
    }
}
