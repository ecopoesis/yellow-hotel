package gbc.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import gbc.core.api.Button
import gbc.core.ppu.SCREEN_H
import gbc.core.ppu.SCREEN_W
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

private fun keyToButton(key: Key): Int = when (key) {
    Key.DirectionRight -> Button.RIGHT
    Key.DirectionLeft -> Button.LEFT
    Key.DirectionUp -> Button.UP
    Key.DirectionDown -> Button.DOWN
    Key.X -> Button.A
    Key.Z -> Button.B
    Key.ShiftLeft, Key.ShiftRight -> Button.SELECT
    Key.Enter -> Button.START
    else -> 0
}

fun main(args: Array<String>) {
    val romFile = File(args.firstOrNull() ?: "Pokemon Yellow.gbc")
    require(romFile.exists()) { "ROM not found: ${romFile.absolutePath}" }

    val emulator = Emulator(romFile, JavaxAudioSink.open())
    thread(name = "emulator", isDaemon = true) { emulator.run() }

    application {
        Window(
            onCloseRequest = {
                emulator.stop()
                exitApplication()
            },
            title = "yellow-hotel — ${romFile.nameWithoutExtension}",
            state = rememberWindowState(width = (SCREEN_W * 3).dp, height = (SCREEN_H * 3 + 28).dp),
        ) {
            var painter by remember { mutableStateOf<BitmapPainter?>(null) }
            val pixels = remember { ByteArray(SCREEN_W * SCREEN_H * 4) }

            androidx.compose.runtime.LaunchedEffect(Unit) {
                val info = ImageInfo(SCREEN_W, SCREEN_H, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                while (true) {
                    val frame = emulator.frame.get()
                    var i = 0
                    for (px in frame) {
                        pixels[i++] = px.toByte() // B
                        pixels[i++] = (px shr 8).toByte() // G
                        pixels[i++] = (px shr 16).toByte() // R
                        pixels[i++] = -1 // A
                    }
                    val bitmap = Bitmap()
                    bitmap.allocPixels(info)
                    bitmap.installPixels(info, pixels, SCREEN_W * 4)
                    painter = BitmapPainter(
                        bitmap.asComposeImageBitmap(),
                        filterQuality = FilterQuality.None,
                    )
                    delay(15)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .onPreviewKeyEvent { event ->
                        val mask = keyToButton(event.key)
                        if (mask != 0) {
                            when (event.type) {
                                KeyEventType.KeyDown -> emulator.buttons = emulator.buttons or mask
                                KeyEventType.KeyUp -> emulator.buttons = emulator.buttons and mask.inv()
                            }
                            true
                        } else {
                            false
                        }
                    },
            ) {
                painter?.let {
                    Image(
                        painter = it,
                        contentDescription = "screen",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}
