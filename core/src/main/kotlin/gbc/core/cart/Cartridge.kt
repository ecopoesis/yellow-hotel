package gbc.core.cart

import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.some

sealed interface MbcState {
    data object NoMbc : MbcState

    data class Mbc1(
        val ramEnabled: Boolean = false,
        val bankLo: Int = 1, // 5-bit register, raw value
        val bankHi: Int = 0, // 2-bit register
        val mode: Int = 0,
    ) : MbcState

    data class Mbc5(
        val ramEnabled: Boolean = false,
        val romBank: Int = 1, // 9-bit register, raw value
        val ramBank: Int = 0,
    ) : MbcState
}

sealed interface SaveError {
    data class WrongSize(val expected: Int, val actual: Int) : SaveError
}

data class CartridgeState(
    val header: CartHeader,
    val rom: ByteArray,
    val ram: ByteArray,
    val mbc: MbcState,
    val hasBattery: Boolean,
) {
    val romBankCount: Int get() = rom.size / ROM_BANK_SIZE
    val ramBankCount: Int get() = ram.size / RAM_BANK_SIZE

    // Identity semantics: the bulk arrays are owned, never structurally compared.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

const val ROM_BANK_SIZE = 0x4000
const val RAM_BANK_SIZE = 0x2000

private val BATTERY_TYPES = setOf(0x03, 0x06, 0x09, 0x0D, 0x0F, 0x10, 0x13, 0x1B, 0x1E, 0x22, 0xFF)

fun parseCartridge(bytes: ByteArray): Either<RomError, CartridgeState> = either {
    val header = parseHeader(bytes).bind()
    val mbc = when (header.cartType) {
        0x00 -> MbcState.NoMbc
        in 0x01..0x03 -> MbcState.Mbc1()
        in 0x19..0x1E -> MbcState.Mbc5()
        else -> raise(RomError.UnsupportedMbc(header.cartType))
    }
    CartridgeState(
        header = header,
        rom = bytes,
        ram = ByteArray(header.ramBytes) { -1 }, // uninitialized cart RAM reads as 0xFF
        mbc = mbc,
        hasBattery = header.cartType in BATTERY_TYPES,
    )
}

/** Total: any address the bus routes here yields a byte; absent/disabled RAM reads 0xFF. */
fun cartRead(cart: CartridgeState, addr: Int): Int = when {
    addr < ROM_BANK_SIZE -> cart.rom[lowRomBank(cart) * ROM_BANK_SIZE + addr].toInt() and 0xFF
    addr < 0x8000 -> cart.rom[highRomBank(cart) * ROM_BANK_SIZE + (addr - ROM_BANK_SIZE)].toInt() and 0xFF
    else -> {
        val bank = ramBank(cart)
        if (ramEnabled(cart.mbc) && bank >= 0) {
            cart.ram[bank * RAM_BANK_SIZE + (addr - 0xA000)].toInt() and 0xFF
        } else {
            0xFF
        }
    }
}

/** Total: ROM-area writes update MBC registers; RAM-area writes mutate the owned RAM array. */
fun cartWrite(cart: CartridgeState, addr: Int, value: Int): CartridgeState = when {
    addr < 0x8000 -> cart.copy(mbc = mbcRegisterWrite(cart.mbc, addr, value))
    else -> {
        val bank = ramBank(cart)
        if (ramEnabled(cart.mbc) && bank >= 0) {
            cart.ram[bank * RAM_BANK_SIZE + (addr - 0xA000)] = value.toByte()
        }
        cart
    }
}

fun batteryRam(cart: CartridgeState): Option<ByteArray> =
    if (cart.hasBattery && cart.ram.isNotEmpty()) cart.ram.copyOf().some() else None

fun loadBatteryRam(cart: CartridgeState, image: ByteArray): Either<SaveError, CartridgeState> {
    if (image.size != cart.ram.size) {
        return SaveError.WrongSize(expected = cart.ram.size, actual = image.size).left()
    }
    image.copyInto(cart.ram)
    return Either.Right(cart)
}

private fun mbcRegisterWrite(mbc: MbcState, addr: Int, value: Int): MbcState = when (mbc) {
    MbcState.NoMbc -> mbc
    is MbcState.Mbc1 -> when (addr) {
        in 0x0000..0x1FFF -> mbc.copy(ramEnabled = (value and 0x0F) == 0x0A)
        in 0x2000..0x3FFF -> mbc.copy(bankLo = value and 0x1F)
        in 0x4000..0x5FFF -> mbc.copy(bankHi = value and 0x03)
        else -> mbc.copy(mode = value and 0x01)
    }
    is MbcState.Mbc5 -> when (addr) {
        in 0x0000..0x1FFF -> mbc.copy(ramEnabled = (value and 0x0F) == 0x0A)
        in 0x2000..0x2FFF -> mbc.copy(romBank = (mbc.romBank and 0x100) or value)
        in 0x3000..0x3FFF -> mbc.copy(romBank = (mbc.romBank and 0xFF) or ((value and 0x01) shl 8))
        in 0x4000..0x5FFF -> mbc.copy(ramBank = value and 0x0F)
        else -> mbc
    }
}

private fun lowRomBank(cart: CartridgeState): Int = when (val mbc = cart.mbc) {
    is MbcState.Mbc1 ->
        if (mbc.mode == 1) (mbc.bankHi shl 5) and (cart.romBankCount - 1) else 0
    else -> 0
}

private fun highRomBank(cart: CartridgeState): Int = when (val mbc = cart.mbc) {
    MbcState.NoMbc -> 1
    is MbcState.Mbc1 -> {
        val lo = if (mbc.bankLo == 0) 1 else mbc.bankLo
        ((mbc.bankHi shl 5) or lo) and (cart.romBankCount - 1)
    }
    is MbcState.Mbc5 -> mbc.romBank and (cart.romBankCount - 1)
}

/** Selected RAM bank, or -1 when the cart has no RAM. */
private fun ramBank(cart: CartridgeState): Int = when {
    cart.ram.isEmpty() -> -1
    else -> when (val mbc = cart.mbc) {
        MbcState.NoMbc -> 0
        is MbcState.Mbc1 -> if (mbc.mode == 1) mbc.bankHi and (cart.ramBankCount - 1) else 0
        is MbcState.Mbc5 -> mbc.ramBank and (cart.ramBankCount - 1)
    }
}

private fun ramEnabled(mbc: MbcState): Boolean = when (mbc) {
    MbcState.NoMbc -> true
    is MbcState.Mbc1 -> mbc.ramEnabled
    is MbcState.Mbc5 -> mbc.ramEnabled
}
