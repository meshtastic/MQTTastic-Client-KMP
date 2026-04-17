/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * Licensed under GPL-3.0-only.
 *
 * Derivative work of the Meshtastic® M logomark, used under the Meshtastic licensing &
 * trademark guidelines (https://meshtastic.org/docs/legal/licensing-and-trademark/).
 * Logomark source: https://github.com/meshtastic/design (logo/svg/Mesh_Logo_Dynamic.svg).
 */
package org.meshtastic.mqtt.sample

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * MQTTastic brand mark — rounded tilted-square (diamond) badge in Meshtastic green carrying the
 * Meshtastic® M logomark. Geometry is identical to the Android adaptive launcher icon so the
 * in-app top-bar mark, desktop window icon, web favicon, and Android launcher all read as
 * pixel-perfect siblings.
 *
 * Canvas: 108×108. Diamond vertices at (54,9)/(99,54)/(54,99)/(9,54) with 8-unit rounded
 * corners. The embedded M uses the flattened 100×55 logomark paths scaled to 0.55 and centered.
 */
@Suppress("MagicNumber")
internal val MqttasticIcon: ImageVector = ImageVector.Builder(
    name = "MqttasticIcon",
    defaultWidth = 108.dp,
    defaultHeight = 108.dp,
    viewportWidth = 108f,
    viewportHeight = 108f,
).apply {
    // Green diamond badge.
    path(fill = SolidColor(Color(0xFF67EA94))) {
        moveTo(59.66f, 14.66f)
        lineTo(93.34f, 48.34f)
        quadTo(99f, 54f, 93.34f, 59.66f)
        lineTo(59.66f, 93.34f)
        quadTo(54f, 99f, 48.34f, 93.34f)
        lineTo(14.66f, 59.66f)
        quadTo(9f, 54f, 14.66f, 48.34f)
        lineTo(48.34f, 14.66f)
        quadTo(54f, 9f, 59.66f, 14.66f)
        close()
    }
    // Meshtastic M, dark-on-green. Source paths live at 100×55; scale 0.55 and center on (54,54):
    //   x' = 26.5 + 0.55 * x,  y' = 38.875 + 0.55 * y.
    path(fill = SolidColor(Color(0xFF2C2D3C))) {
        moveTo(62.094f, 46.065f)
        lineTo(47.327f, 67.721f)
        lineTo(44.212f, 65.597f)
        lineTo(60.533f, 41.662f)
        curveTo(60.884f, 41.148f, 61.466f, 40.840f, 62.089f, 40.839f)
        curveTo(62.711f, 40.839f, 63.294f, 41.145f, 63.646f, 41.659f)
        lineTo(80.005f, 65.557f)
        lineTo(76.894f, 67.686f)
        lineTo(62.094f, 46.065f)
        close()
    }
    path(fill = SolidColor(Color(0xFF2C2D3C))) {
        moveTo(31.108f, 67.698f)
        lineTo(48.358f, 42.403f)
        lineTo(45.243f, 40.279f)
        lineTo(27.993f, 65.574f)
        lineTo(31.108f, 67.698f)
        close()
    }
}.build()
