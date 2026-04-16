import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.meshtastic.mqtt.sample.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "MQTTtastic Sample",
    ) {
        App()
    }
}
