package gbc.core.cart

import gbc.fixtures.SyntheticRom
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** MBC1, 64 KiB ROM (4 banks) — the shape of Blargg's combined cpu_instrs.gb. */
private fun smallMbc1(): CartridgeState =
    parseCartridge(SyntheticRom.banked(romSizeCode = 0x01, cartType = 0x01)).shouldBeRight()

/** MBC1 + RAM + battery, 2 MiB ROM (128 banks), 32 KiB RAM — large-cart banking shape. */
private fun largeMbc1(): CartridgeState =
    parseCartridge(SyntheticRom.banked(romSizeCode = 0x06, cartType = 0x03, ramSizeCode = 0x03)).shouldBeRight()

private fun CartridgeState.visibleBank(region: Int): Int =
    SyntheticRom.stampAt({ addr -> cartRead(this, addr) }, region)

class Mbc1Spec : FunSpec({

    test("power-on maps bank 0 at 0x0000 and bank 1 at 0x4000") {
        smallMbc1().visibleBank(0x0000) shouldBe 0
        smallMbc1().visibleBank(0x4000) shouldBe 1
    }

    test("writing 0 to the bank register selects bank 1 (the 0->1 quirk)") {
        cartWrite(smallMbc1(), 0x2000, 0).visibleBank(0x4000) shouldBe 1
    }

    test("the 5-bit bank register is masked to the cart's bank count") {
        // 4-bank cart: requesting bank 6 masks to bank 2
        cartWrite(smallMbc1(), 0x2000, 6).visibleBank(0x4000) shouldBe 2
    }

    test("the 0->1 quirk applies before masking: bank 0x20 on a large cart maps to 0x21 via upper bits") {
        // Write 0 to 5-bit register, upper bits = 1 -> bank 0x21 at 0x4000
        var cart = cartWrite(largeMbc1(), 0x2000, 0)
        cart = cartWrite(cart, 0x4000, 1)
        cart.visibleBank(0x4000) shouldBe 0x21
    }

    test("in mode 0 the 0x0000 region stays bank 0 regardless of upper bits") {
        val cart = cartWrite(largeMbc1(), 0x4000, 1)
        cart.visibleBank(0x0000) shouldBe 0
    }

    test("in mode 1 the upper bits remap the 0x0000 region to bank 0x20/0x40/0x60") {
        var cart = cartWrite(largeMbc1(), 0x6000, 1)
        cart = cartWrite(cart, 0x4000, 1)
        cart.visibleBank(0x0000) shouldBe 0x20
    }

    test("in mode 0 cart RAM always uses bank 0") {
        var cart = cartWrite(largeMbc1(), 0x0000, 0x0A)
        cart = cartWrite(cart, 0xA000, 0x55)
        cart = cartWrite(cart, 0x4000, 1) // upper bits ignored for RAM in mode 0
        cartRead(cart, 0xA000) shouldBe 0x55
    }

    test("in mode 1 the upper bits select the RAM bank") {
        var cart = cartWrite(largeMbc1(), 0x0000, 0x0A)
        cart = cartWrite(cart, 0x6000, 1)
        cart = cartWrite(cart, 0x4000, 0)
        cart = cartWrite(cart, 0xA000, 0x11)
        cart = cartWrite(cart, 0x4000, 1)
        cart = cartWrite(cart, 0xA000, 0x22)
        cart = cartWrite(cart, 0x4000, 0)
        cartRead(cart, 0xA000) shouldBe 0x11
        cart = cartWrite(cart, 0x4000, 1)
        cartRead(cart, 0xA000) shouldBe 0x22
    }

    test("RAM enable requires low nibble 0x0A") {
        var cart = cartWrite(largeMbc1(), 0x0000, 0x1A) // low nibble 0xA -> enabled
        cart = cartWrite(cart, 0xA000, 0x99)
        cartRead(cart, 0xA000) shouldBe 0x99
        cart = cartWrite(cart, 0x0000, 0x0B)
        cartRead(cart, 0xA000) shouldBe 0xFF
    }
})
