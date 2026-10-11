package com.sethu.emergency.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object SethuIcons {
    val Logo: ImageVector by lazy {
        ImageVector.Builder(
            name = "SethuLogo",
            defaultWidth = 32.dp,
            defaultHeight = 32.dp,
            viewportWidth = 32f,
            viewportHeight = 32f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF00DF81)),
                strokeLineWidth = 3.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 26f)
                lineTo(16f, 6f)
                lineTo(26f, 26f)
            }
            path(
                stroke = SolidColor(Color(0xFF00B867)),
                strokeLineWidth = 3f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(11f, 18f)
                lineTo(21f, 18f)
            }
        }.build()
    }

    val Mic: ImageVector by lazy {
        ImageVector.Builder(
            name = "Mic",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF00DF81)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Mic capsule
                moveTo(12f, 2f)
                curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
                verticalLineTo(11f)
                curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
                curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
                verticalLineTo(5f)
                curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
                close()
                // Stand arc
                moveTo(19f, 10f)
                verticalLineTo(11f)
                curveTo(19f, 14.87f, 15.87f, 18f, 12f, 18f)
                curveTo(8.13f, 18f, 5f, 14.87f, 5f, 11f)
                verticalLineTo(10f)
                // Stem
                moveTo(12f, 18f)
                verticalLineTo(22f)
                moveTo(8f, 22f)
                horizontalLineTo(16f)
            }
        }.build()
    }

    val Shield: ImageVector by lazy {
        ImageVector.Builder(
            name = "Shield",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF00DF81)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 22f)
                curveTo(12f, 22f, 20f, 18f, 20f, 12f)
                verticalLineTo(5f)
                lineTo(12f, 2f)
                lineTo(4f, 5f)
                verticalLineTo(12f)
                curveTo(4f, 18f, 12f, 22f, 12f, 22f)
                close()
            }
            path(
                stroke = SolidColor(Color(0xFF00DF81)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9f, 12f)
                lineTo(11f, 14f)
                lineTo(15f, 10f)
            }
        }.build()
    }

    val SendPlane: ImageVector by lazy {
        ImageVector.Builder(
            name = "SendPlane",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF00DF81)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(22f, 2f)
                lineTo(11f, 13f)
                moveTo(22f, 2f)
                lineTo(15f, 22f)
                lineTo(11f, 13f)
                lineTo(2f, 9f)
                close()
            }
        }.build()
    }

    val RelayNodes: ImageVector by lazy {
        ImageVector.Builder(
            name = "RelayNodes",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF00DF81)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Node 1 (left)
                moveTo(6f, 12f)
                lineTo(16f, 6f)
                moveTo(6f, 12f)
                lineTo(16f, 18f)
            }
            path(fill = SolidColor(Color(0xFF00DF81))) {
                // Circle at 6, 12
                moveTo(6f, 9.5f)
                curveTo(7.38f, 9.5f, 8.5f, 10.62f, 8.5f, 12f)
                curveTo(8.5f, 13.38f, 7.38f, 14.5f, 6f, 14.5f)
                curveTo(4.62f, 14.5f, 3.5f, 13.38f, 3.5f, 12f)
                curveTo(3.5f, 10.62f, 4.62f, 9.5f, 6f, 9.5f)
                close()

                // Circle at 18, 6
                moveTo(18f, 3.5f)
                curveTo(19.38f, 3.5f, 20.5f, 4.62f, 20.5f, 6f)
                curveTo(20.5f, 7.38f, 19.38f, 8.5f, 18f, 8.5f)
                curveTo(16.62f, 8.5f, 15.5f, 7.38f, 15.5f, 6f)
                curveTo(15.5f, 4.62f, 16.62f, 3.5f, 18f, 3.5f)
                close()

                // Circle at 18, 18
                moveTo(18f, 15.5f)
                curveTo(19.38f, 15.5f, 20.5f, 16.62f, 20.5f, 18f)
                curveTo(20.5f, 19.38f, 19.38f, 20.5f, 18f, 20.5f)
                curveTo(16.62f, 20.5f, 15.5f, 19.38f, 15.5f, 18f)
                curveTo(15.5f, 16.62f, 16.62f, 15.5f, 18f, 15.5f)
                close()
            }
        }.build()
    }

    val CoordinatorTeam: ImageVector by lazy {
        ImageVector.Builder(
            name = "CoordinatorTeam",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF00DF81)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Center person
                moveTo(12f, 8f)
                curveTo(13.66f, 8f, 15f, 6.66f, 15f, 5f)
                curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
                curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
                curveTo(9f, 6.66f, 10.34f, 8f, 12f, 8f)
                close()
                moveTo(6f, 19f)
                verticalLineTo(17f)
                curveTo(6f, 14.79f, 8.69f, 13f, 12f, 13f)
                curveTo(15.31f, 13f, 18f, 14.79f, 18f, 17f)
                verticalLineTo(19f)
                // Side people shoulders
                moveTo(4f, 17f)
                curveTo(4f, 15.5f, 4.8f, 14.2f, 6.2f, 13.5f)
                moveTo(20f, 17f)
                curveTo(20f, 15.5f, 19.2f, 14.2f, 17.8f, 13.5f)
            }
        }.build()
    }

    val ChevronRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "ChevronRight",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF86978E)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9f, 18f)
                lineTo(15f, 12f)
                lineTo(9f, 6f)
            }
        }.build()
    }

    val ArrowBack: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowBack",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(15f, 18f)
                lineTo(9f, 12f)
                lineTo(15f, 6f)
            }
        }.build()
    }

    val HelpOutline: ImageVector by lazy {
        ImageVector.Builder(
            name = "HelpOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF86978E)),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Circle
                moveTo(12f, 22f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
                close()
                // Question mark
                moveTo(9.5f, 9.5f)
                curveTo(9.5f, 8f, 10.6f, 7f, 12f, 7f)
                curveTo(13.4f, 7f, 14.5f, 8.1f, 14.5f, 9.5f)
                curveTo(14.5f, 11f, 13f, 11.8f, 12f, 12.8f)
                verticalLineTo(14f)
            }
            path(fill = SolidColor(Color(0xFF86978E))) {
                moveTo(12f, 16f)
                curveTo(12.55f, 16f, 13f, 16.45f, 13f, 17f)
                curveTo(13f, 17.55f, 12.55f, 18f, 12f, 18f)
                curveTo(11.45f, 18f, 11f, 17.55f, 11f, 17f)
                curveTo(11f, 16.45f, 11.45f, 16f, 12f, 16f)
                close()
            }
        }.build()
    }

    val AlertTriangle: ImageVector by lazy {
        ImageVector.Builder(
            name = "AlertTriangle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(10.29f, 3.86f)
                lineTo(1.82f, 18f)
                curveTo(1.4f, 18.73f, 1.93f, 19.64f, 2.78f, 19.64f)
                horizontalLineTo(21.22f)
                curveTo(22.07f, 19.64f, 22.6f, 18.73f, 22.18f, 18f)
                lineTo(13.71f, 3.86f)
                curveTo(13.29f, 3.13f, 12.21f, 3.13f, 11.79f, 3.86f)
                close()
                moveTo(12f, 9f)
                verticalLineTo(13f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 16f)
                curveTo(12.55f, 16f, 13f, 16.45f, 13f, 17f)
                curveTo(13f, 17.55f, 12.55f, 18f, 12f, 18f)
                curveTo(11.45f, 18f, 11f, 17.55f, 11f, 17f)
                curveTo(11f, 16.45f, 11.45f, 16f, 12f, 16f)
                close()
            }
        }.build()
    }

    val BroadcastTower: ImageVector by lazy {
        ImageVector.Builder(
            name = "BroadcastTower",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF86978E)),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round
            ) {
                // Center dot
                moveTo(12f, 12f)
                // Left arc 1
                moveTo(8.5f, 8.5f)
                curveTo(6.55f, 10.45f, 6.55f, 13.55f, 8.5f, 15.5f)
                // Right arc 1
                moveTo(15.5f, 8.5f)
                curveTo(17.45f, 10.45f, 17.45f, 13.55f, 15.5f, 15.5f)
                // Left arc 2
                moveTo(5f, 5f)
                curveTo(1.1f, 8.9f, 1.1f, 15.1f, 5f, 19f)
                // Right arc 2
                moveTo(19f, 5f)
                curveTo(22.9f, 8.9f, 22.9f, 15.1f, 19f, 19f)
            }
            path(fill = SolidColor(Color(0xFF86978E))) {
                moveTo(12f, 10.5f)
                curveTo(12.83f, 10.5f, 13.5f, 11.17f, 13.5f, 12f)
                curveTo(13.5f, 12.83f, 12.83f, 13.5f, 12f, 13.5f)
                curveTo(11.17f, 13.5f, 10.5f, 12.83f, 10.5f, 12f)
                curveTo(10.5f, 11.17f, 11.17f, 10.5f, 12f, 10.5f)
                close()
            }
        }.build()
    }

    // Emergency Type Icons
    val WaveFlood: ImageVector by lazy {
        ImageVector.Builder(
            name = "WaveFlood",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF00DF81)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 12f)
                curveTo(5f, 9.5f, 7f, 9.5f, 9f, 12f)
                curveTo(11f, 14.5f, 13f, 14.5f, 15f, 12f)
                curveTo(17f, 9.5f, 19f, 9.5f, 21f, 12f)

                moveTo(3f, 17f)
                curveTo(5f, 14.5f, 7f, 14.5f, 9f, 17f)
                curveTo(11f, 19.5f, 13f, 19.5f, 15f, 17f)
                curveTo(17f, 14.5f, 19f, 14.5f, 21f, 17f)
            }
        }.build()
    }

    val MedicalCross: ImageVector by lazy {
        ImageVector.Builder(
            name = "MedicalCross",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF00DF81)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9f, 3f)
                horizontalLineTo(15f)
                verticalLineTo(9f)
                horizontalLineTo(21f)
                verticalLineTo(15f)
                horizontalLineTo(15f)
                verticalLineTo(21f)
                horizontalLineTo(9f)
                verticalLineTo(15f)
                horizontalLineTo(3f)
                verticalLineTo(9f)
                horizontalLineTo(9f)
                close()
            }
        }.build()
    }

    val Fire: ImageVector by lazy {
        ImageVector.Builder(
            name = "Fire",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF00DF81)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 22f)
                curveTo(16.42f, 22f, 20f, 18.42f, 20f, 14f)
                curveTo(20f, 10f, 16.5f, 6.5f, 14.5f, 3f)
                curveTo(14.5f, 6f, 12f, 8f, 10f, 9f)
                curveTo(8f, 7.5f, 7.5f, 5.5f, 7.5f, 5f)
                curveTo(5.5f, 7.5f, 4f, 10.5f, 4f, 14f)
                curveTo(4f, 18.42f, 7.58f, 22f, 12f, 22f)
                close()
            }
        }.build()
    }

    val Trapped: ImageVector by lazy {
        ImageVector.Builder(
            name = "Trapped",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF00DF81)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(17f, 21f)
                verticalLineTo(19f)
                curveTo(17f, 16.79f, 15.21f, 15f, 13f, 15f)
                horizontalLineTo(7f)
                curveTo(4.79f, 15f, 3f, 16.79f, 3f, 19f)
                verticalLineTo(21f)
                moveTo(10f, 11f)
                curveTo(12.21f, 11f, 14f, 9.21f, 14f, 7f)
                curveTo(14f, 4.79f, 12.21f, 3f, 10f, 3f)
                curveTo(7.79f, 3f, 6f, 4.79f, 6f, 7f)
                curveTo(6f, 9.21f, 7.79f, 11f, 10f, 11f)
                close()
                moveTo(21f, 21f)
                verticalLineTo(19f)
                curveTo(21f, 17.5f, 20.1f, 16.2f, 18.7f, 15.6f)
            }
        }.build()
    }

    val Pill: ImageVector by lazy {
        ImageVector.Builder(
            name = "Pill",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF00DF81)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(10.5f, 20.5f)
                lineTo(3.5f, 13.5f)
                curveTo(1.5f, 11.5f, 1.5f, 8.5f, 3.5f, 6.5f)
                curveTo(5.5f, 4.5f, 8.5f, 4.5f, 10.5f, 6.5f)
                lineTo(17.5f, 13.5f)
                curveTo(19.5f, 15.5f, 19.5f, 18.5f, 17.5f, 20.5f)
                curveTo(15.5f, 22.5f, 12.5f, 22.5f, 10.5f, 20.5f)
                close()
                moveTo(7f, 10f)
                lineTo(14f, 17f)
            }
        }.build()
    }

    val MoreDots: ImageVector by lazy {
        ImageVector.Builder(
            name = "MoreDots",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color(0xFF00DF81))) {
                moveTo(5f, 12f)
                curveTo(5f, 13.1f, 4.1f, 14f, 3f, 14f)
                curveTo(1.9f, 14f, 1f, 13.1f, 1f, 12f)
                curveTo(1f, 10.9f, 1.9f, 10f, 3f, 10f)
                curveTo(4.1f, 10f, 5f, 10.9f, 5f, 12f)
                close()
                moveTo(14f, 12f)
                curveTo(14f, 13.1f, 13.1f, 14f, 12f, 14f)
                curveTo(10.9f, 14f, 10f, 13.1f, 10f, 12f)
                curveTo(10f, 10.9f, 10.9f, 10f, 12f, 10f)
                curveTo(13.1f, 10f, 14f, 10.9f, 14f, 12f)
                close()
                moveTo(23f, 12f)
                curveTo(23f, 13.1f, 22.1f, 14f, 21f, 14f)
                curveTo(19.9f, 14f, 19f, 13.1f, 19f, 12f)
                curveTo(19f, 10.9f, 19.9f, 10f, 21f, 10f)
                curveTo(22.1f, 10f, 23f, 10.9f, 23f, 12f)
                close()
            }
        }.build()
    }

    // Bottom Navigation Icons
    val NavHome: ImageVector by lazy {
        ImageVector.Builder(
            name = "NavHome",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 9.5f)
                lineTo(12f, 2.5f)
                lineTo(21f, 9.5f)
                verticalLineTo(20f)
                curveTo(21f, 20.55f, 20.55f, 21f, 20f, 21f)
                horizontalLineTo(4f)
                curveTo(3.45f, 21f, 3f, 20.55f, 3f, 20f)
                close()
            }
        }.build()
    }

    val NavMessages: ImageVector by lazy {
        ImageVector.Builder(
            name = "NavMessages",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF86978E)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(21f, 15f)
                curveTo(21f, 15.55f, 20.55f, 16f, 20f, 16f)
                horizontalLineTo(7f)
                lineTo(3f, 20f)
                verticalLineTo(5f)
                curveTo(3f, 4.45f, 3.45f, 4f, 4f, 4f)
                horizontalLineTo(20f)
                curveTo(20.55f, 4f, 21f, 4.45f, 21f, 5f)
                close()
            }
        }.build()
    }

    val NavMap: ImageVector by lazy {
        ImageVector.Builder(
            name = "NavMap",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF86978E)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 2f)
                curveTo(8.13f, 2f, 5f, 5.13f, 5f, 9f)
                curveTo(5f, 14.25f, 12f, 22f, 12f, 22f)
                curveTo(12f, 22f, 19f, 14.25f, 19f, 9f)
                curveTo(19f, 5.13f, 15.87f, 2f, 12f, 2f)
                close()
                moveTo(12f, 11.5f)
                curveTo(10.62f, 11.5f, 9.5f, 10.38f, 9.5f, 9f)
                curveTo(9.5f, 7.62f, 10.62f, 6.5f, 12f, 6.5f)
                curveTo(13.38f, 6.5f, 14.5f, 7.62f, 14.5f, 9f)
                curveTo(14.5f, 10.38f, 13.38f, 11.5f, 12f, 11.5f)
                close()
            }
        }.build()
    }

    val NavSettings: ImageVector by lazy {
        ImageVector.Builder(
            name = "NavSettings",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF86978E)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 15f)
                curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
                curveTo(15f, 10.34f, 13.66f, 9f, 12f, 9f)
                curveTo(10.34f, 9f, 9f, 10.34f, 9f, 12f)
                curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
                close()
                moveTo(19.4f, 15f)
                lineTo(20.4f, 16.7f)
                lineTo(18.7f, 19.7f)
                lineTo(16.8f, 18.9f)
                lineTo(15.1f, 20.4f)
                lineTo(14.8f, 22.4f)
                horizontalLineTo(11.2f)
                lineTo(10.9f, 20.4f)
                lineTo(9.2f, 18.9f)
                lineTo(7.3f, 19.7f)
                lineTo(5.6f, 16.7f)
                lineTo(6.6f, 15f)
            }
        }.build()
    }

    // Coordinator Graphic Icons
    val MapFold: ImageVector by lazy {
        ImageVector.Builder(
            name = "MapFold",
            defaultWidth = 48.dp,
            defaultHeight = 48.dp,
            viewportWidth = 48f,
            viewportHeight = 48f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF6B7C72)),
                strokeLineWidth = 2.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 14f)
                lineTo(18f, 8f)
                lineTo(30f, 14f)
                lineTo(42f, 8f)
                verticalLineTo(34f)
                lineTo(30f, 40f)
                lineTo(18f, 34f)
                lineTo(6f, 40f)
                close()
                moveTo(18f, 8f)
                verticalLineTo(34f)
                moveTo(30f, 14f)
                verticalLineTo(40f)
            }
        }.build()
    }

    val LocationBeacon: ImageVector by lazy {
        ImageVector.Builder(
            name = "LocationBeacon",
            defaultWidth = 32.dp,
            defaultHeight = 32.dp,
            viewportWidth = 32f,
            viewportHeight = 32f
        ).apply {
            path(fill = SolidColor(Color(0xFF00DF81))) {
                moveTo(16f, 4f)
                curveTo(12.13f, 4f, 9f, 7.13f, 9f, 11f)
                curveTo(9f, 16.25f, 16f, 24f, 16f, 24f)
                curveTo(16f, 24f, 23f, 16.25f, 23f, 11f)
                curveTo(23f, 7.13f, 19.87f, 4f, 16f, 4f)
                close()
                moveTo(16f, 13.5f)
                curveTo(14.62f, 13.5f, 13.5f, 12.38f, 13.5f, 11f)
                curveTo(13.5f, 9.62f, 14.62f, 8.5f, 16f, 8.5f)
                curveTo(17.38f, 8.5f, 18.5f, 9.62f, 18.5f, 11f)
                curveTo(18.5f, 12.38f, 17.38f, 13.5f, 16f, 13.5f)
                close()
            }
        }.build()
    }

    val DocumentList: ImageVector by lazy {
        ImageVector.Builder(
            name = "DocumentList",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF86978E)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(14f, 2f)
                horizontalLineTo(6f)
                curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
                verticalLineTo(20f)
                curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
                horizontalLineTo(18f)
                curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
                verticalLineTo(8f)
                lineTo(14f, 2f)
                close()
                moveTo(8f, 13f)
                horizontalLineTo(16f)
                moveTo(8f, 17f)
                horizontalLineTo(16f)
            }
        }.build()
    }

    val PriorityFilter: ImageVector by lazy {
        ImageVector.Builder(
            name = "PriorityFilter",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF86978E)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 6f)
                horizontalLineTo(20f)
                moveTo(7f, 12f)
                horizontalLineTo(17f)
                moveTo(10f, 18f)
                horizontalLineTo(14f)
            }
        }.build()
    }

    val ClusterIcon: ImageVector by lazy {
        ImageVector.Builder(
            name = "ClusterIcon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF86978E)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(17f, 20f)
                curveTo(17f, 17.5f, 15f, 16f, 12f, 16f)
                curveTo(9f, 16f, 7f, 17.5f, 7f, 20f)
                moveTo(12f, 13f)
                curveTo(13.66f, 13f, 15f, 11.66f, 15f, 10f)
                curveTo(15f, 8.34f, 13.66f, 7f, 12f, 7f)
                curveTo(10.34f, 7f, 9f, 8.34f, 9f, 10f)
                curveTo(9f, 11.66f, 10.34f, 13f, 12f, 13f)
                close()
            }
        }.build()
    }

    val OfflineMapIcon: ImageVector by lazy {
        ImageVector.Builder(
            name = "OfflineMapIcon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF86978E)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 6f)
                lineTo(9f, 3f)
                lineTo(15f, 6f)
                lineTo(21f, 3f)
                verticalLineTo(18f)
                lineTo(15f, 21f)
                lineTo(9f, 18f)
                lineTo(3f, 21f)
                close()
                moveTo(9f, 3f)
                verticalLineTo(18f)
                moveTo(15f, 6f)
                verticalLineTo(21f)
            }
        }.build()
    }

    val AutoAwesome: ImageVector by lazy {
        ImageVector.Builder(
            name = "AutoAwesome",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF00DF81)),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Sparkle 1 (center large)
                moveTo(12f, 3f)
                lineTo(14f, 9f)
                lineTo(20f, 11f)
                lineTo(14f, 13f)
                lineTo(12f, 19f)
                lineTo(10f, 13f)
                lineTo(4f, 11f)
                lineTo(10f, 9f)
                close()
                // Mini sparkle
                moveTo(19f, 17f)
                lineTo(20f, 19f)
                lineTo(22f, 20f)
                lineTo(20f, 21f)
                lineTo(19f, 23f)
                lineTo(18f, 21f)
                lineTo(16f, 20f)
                lineTo(18f, 19f)
                close()
            }
        }.build()
    }

    val Refresh: ImageVector by lazy {
        ImageVector.Builder(
            name = "Refresh",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color(0xFF00DF81)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(23f, 4f)
                verticalLineTo(10f)
                horizontalLineTo(17f)
                moveTo(1f, 20f)
                verticalLineTo(14f)
                horizontalLineTo(7f)
                moveTo(3.51f, 9f)
                curveTo(4.01f, 7.56f, 4.87f, 6.26f, 6.01f, 5.25f)
                curveTo(7.15f, 4.24f, 8.52f, 3.55f, 10f, 3.25f)
                curveTo(11.48f, 2.95f, 13.01f, 3.05f, 14.44f, 3.55f)
                curveTo(15.86f, 4.05f, 17.13f, 4.93f, 18.11f, 6.1f)
                lineTo(23f, 10f)
                moveTo(1f, 14f)
                lineTo(5.89f, 17.9f)
                curveTo(6.87f, 19.07f, 8.14f, 19.95f, 9.56f, 20.45f)
                curveTo(10.99f, 20.95f, 12.52f, 21.05f, 14f, 20.75f)
                curveTo(15.48f, 20.45f, 16.85f, 19.76f, 17.99f, 18.75f)
                curveTo(19.13f, 17.74f, 19.99f, 16.44f, 20.49f, 15f)
            }
        }.build()
    }
}

