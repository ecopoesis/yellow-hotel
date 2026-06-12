package gbc.core.joypad

/**
 * P1/JOYP matrix: bits 4/5 select the direction/button groups (0 = selected),
 * bits 0-3 read the selected lines (0 = pressed), bits 6-7 read 1.
 */
data class JoypadState(
    val select: Int = 0x30, // neither group selected
    val buttons: Int = 0,   // host-side mask, 1 = pressed (see gbc.core.api.Button)
)

fun p1Read(j: JoypadState): Int {
    var nibble = 0xF
    if (j.select and 0x10 == 0) nibble = nibble and (j.buttons and 0xF).inv()
    if (j.select and 0x20 == 0) nibble = nibble and ((j.buttons shr 4) and 0xF).inv()
    return 0xC0 or j.select or (nibble and 0xF)
}

fun p1Write(j: JoypadState, value: Int): JoypadState = j.copy(select = value and 0x30)
