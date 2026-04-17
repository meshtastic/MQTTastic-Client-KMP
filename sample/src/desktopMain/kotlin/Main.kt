import androidx.compose.runtime.remember
import androidx.compose.ui.res.useResource
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.skia.Image
import org.meshtastic.mqtt.sample.App

fun main() = application {
    val icon = remember {
        BitmapPainter(useResource("icon.png") { Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() })
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "MQTTastic Sample",
        state = rememberWindowState(width = 480.dp, height = 800.dp),
        icon = icon,
    ) {
        App()
    }
}
