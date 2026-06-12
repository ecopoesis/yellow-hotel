package gbc.core.cart

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure

enum class CgbFlag { DmgOnly, CgbCompatible, CgbOnly }

sealed interface RomError {
    data class FileTooSmall(val size: Int) : RomError
    data class HeaderChecksumMismatch(val expected: Int, val actual: Int) : RomError
    data class RomSizeMismatch(val declared: Int, val actual: Int) : RomError
    data class InvalidRomSize(val code: Int) : RomError
    data class InvalidRamSize(val code: Int) : RomError
    data class UnsupportedMbc(val cartType: Int) : RomError
}

data class CartHeader(
    val title: String,
    val cgbFlag: CgbFlag,
    val cartType: Int,
    val romBytes: Int,
    val ramBytes: Int,
    val headerChecksum: Int,
)

private const val TITLE_START = 0x134
private const val TITLE_END = 0x142 // inclusive; 0x143 is the CGB flag
private const val CGB_FLAG = 0x143
private const val CART_TYPE = 0x147
private const val ROM_SIZE = 0x148
private const val RAM_SIZE = 0x149
private const val CHECKSUM = 0x14D
private const val HEADER_END = 0x150

fun parseHeader(rom: ByteArray): Either<RomError, CartHeader> = either {
    ensure(rom.size >= HEADER_END) { RomError.FileTooSmall(rom.size) }

    val computed = headerChecksum(rom)
    val stored = rom[CHECKSUM].toInt() and 0xFF
    ensure(computed == stored) { RomError.HeaderChecksumMismatch(expected = computed, actual = stored) }

    val romBytes = romBytes(rom[ROM_SIZE].toInt() and 0xFF)
    ensure(romBytes == rom.size) { RomError.RomSizeMismatch(declared = romBytes, actual = rom.size) }

    CartHeader(
        title = title(rom),
        cgbFlag = cgbFlag(rom[CGB_FLAG].toInt() and 0xFF),
        cartType = rom[CART_TYPE].toInt() and 0xFF,
        romBytes = romBytes,
        ramBytes = ramBytes(rom[RAM_SIZE].toInt() and 0xFF),
        headerChecksum = stored,
    )
}

/** Same algorithm the boot ROM runs over 0x134..0x14C. */
fun headerChecksum(rom: ByteArray): Int {
    var x = 0
    for (i in TITLE_START..0x14C) x = (x - (rom[i].toInt() and 0xFF) - 1) and 0xFF
    return x
}

private fun title(rom: ByteArray): String =
    (TITLE_START..TITLE_END)
        .map { (rom[it].toInt() and 0xFF).toChar() }
        .joinToString("")
        .trimEnd { it == ' ' || it == '\u0000' }

private fun cgbFlag(value: Int): CgbFlag = when (value) {
    0x80 -> CgbFlag.CgbCompatible
    0xC0 -> CgbFlag.CgbOnly
    else -> CgbFlag.DmgOnly
}

private fun Raise<RomError>.romBytes(code: Int): Int {
    ensure(code <= 0x08) { RomError.InvalidRomSize(code) }
    return 0x8000 shl code
}

private fun Raise<RomError>.ramBytes(code: Int): Int = when (code) {
    0x00 -> 0
    0x01 -> 2 * 1024 // unofficial, seen in homebrew
    0x02 -> 8 * 1024
    0x03 -> 32 * 1024
    0x04 -> 128 * 1024
    0x05 -> 64 * 1024
    else -> raise(RomError.InvalidRamSize(code))
}
