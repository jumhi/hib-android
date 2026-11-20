package com.sameerasw.airsync.multidesk

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph

/*
 * H.I.B Space — the device bubbles shared by the Flick screen, "Teach aim" and the Play chooser.
 * Icons by platform (SPACE.md §2): phone = smartphone, mac = laptop, windows = desktop monitor.
 */

val HibCyan = Color(0xFF20D9FF)
val HibLime = Color(0xFFBAFF38)
val HibInk = Color(0xFF090C21)
val HibPanel = Color(0xFF1A2147)

fun deviceIcon(platform: String?): ImageVector = when (platform) {
    "windows" -> Icons.Rounded.DesktopWindows
    "android", "phone" -> Icons.Rounded.Smartphone
    else -> Icons.Rounded.LaptopMac
}

/** The lime "In use" pill on the computer the user is at right now. */
@Composable
fun InUsePill(modifier: Modifier = Modifier, text: String = "In use") {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(HibLime)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = HibInk,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** A shape morphing between two Material 3 Expressive polygons (0 = [Morph] start, 1 = end). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress, Path())
        val b = path.getBounds()
        if (b.width <= 0f || b.height <= 0f) return Outline.Rectangle(size.toRect())
        path.translate(Offset(-b.left, -b.top))
        path.transform(Matrix().apply { scale(size.width / b.width, size.height / b.height) })
        return Outline.Generic(path)
    }
}

data class BubbleDevice(
    val id: String,
    val name: String,
    val platform: String,
    val inUse: Boolean = false,
    val playing: Boolean = false,
    val subtitle: String? = null
)

/**
 * One device bubble. When [lit] it morphs from a circle into a slowly turning cookie, takes the accent
 * colour and glows. [aimed] adds a small "pointing" badge.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeviceBubble(
    device: BubbleDevice,
    lit: Boolean,
    modifier: Modifier = Modifier,
    aimed: Boolean = false,
    bubbleSize: Dp = 64.dp,
    accent: Color = HibCyan,
    onAccent: Color = HibInk,
    container: Color = HibPanel,
    onContainer: Color = Color.White,
    label: Color = Color.White,
    pulse: Float = 0f,
    onPositioned: ((Offset) -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val glow by animateFloatAsState(
        if (lit) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "bubbleGlow"
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "bubblePress")
    val spin = rememberInfiniteTransition(label = "bubbleSpin")
    val rotation by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart),
        label = "bubbleRotation"
    )

    Column(
        modifier = modifier.width(bubbleSize + 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(bubbleSize)
                .onGloballyPositioned { c ->
                    onPositioned?.invoke(c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 2f))
                }
                .graphicsLayer {
                    val s = (1f + 0.12f * glow + 0.18f * pulse) * pressScale
                    scaleX = s
                    scaleY = s
                }
                .drawBehind {
                    val g = (glow * 0.55f + pulse * 0.4f).coerceIn(0f, 1f)
                    if (g > 0.01f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(accent.copy(alpha = g), accent.copy(alpha = 0f)),
                                center = center,
                                radius = size.minDimension * 1.05f
                            ),
                            radius = size.minDimension * 1.05f
                        )
                    }
                }
                .then(
                    if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null) { onClick() }
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { rotationZ = rotation * glow }
                    .clip(MorphShape(morph, glow))
                    .background(lerp(container, accent, glow))
            )
            Icon(
                imageVector = deviceIcon(device.platform),
                contentDescription = device.name,
                tint = lerp(onContainer, onAccent, glow),
                modifier = Modifier.size(bubbleSize * 0.46f)
            )
            if (aimed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(HibInk)
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.MyLocation, contentDescription = "Pointing at it", tint = accent, modifier = Modifier.size(16.dp))
                }
            } else if (device.playing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(HibInk)
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.VolumeUp, contentDescription = "Playing", tint = HibLime, modifier = Modifier.size(16.dp))
                }
            }
            if (device.inUse) {
                InUsePill(Modifier.align(Alignment.BottomCenter).offset(y = 10.dp))
            }
        }
        Spacer(Modifier.height(if (device.inUse) 16.dp else 10.dp))
        Text(
            text = device.name,
            color = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        device.subtitle?.let {
            Text(
                text = it,
                color = label.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Bubbles across the top in a gentle arc (outer ones a little lower). One computer gets one centred bubble.
 */
@Composable
fun DeviceBubbleArc(
    devices: List<BubbleDevice>,
    litId: String?,
    modifier: Modifier = Modifier,
    aimedId: String? = null,
    pulseId: String? = null,
    pulse: Float = 0f,
    arcDepth: Dp = 20.dp,
    accent: Color = HibCyan,
    onAccent: Color = HibInk,
    container: Color = HibPanel,
    onContainer: Color = Color.White,
    label: Color = Color.White,
    onPositioned: ((String, Offset) -> Unit)? = null,
    onTap: ((String) -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top
    ) {
        val n = devices.size
        devices.forEachIndexed { i, d ->
            val t = if (n <= 1) 0f else (i - (n - 1) / 2f) / ((n - 1) / 2f)
            DeviceBubble(
                device = d,
                lit = PairedDesktops.sameName(d.id, litId ?: ""),
                aimed = aimedId != null && PairedDesktops.sameName(d.id, aimedId),
                accent = accent,
                onAccent = onAccent,
                container = container,
                onContainer = onContainer,
                label = label,
                pulse = if (pulseId != null && PairedDesktops.sameName(d.id, pulseId)) pulse else 0f,
                bubbleSize = if (n > 3) 56.dp else 64.dp,
                modifier = Modifier.offset(y = arcDepth * t * t),
                onPositioned = onPositioned?.let { cb -> { p: Offset -> cb(d.id, p) } },
                onClick = onTap?.let { cb -> { cb(d.id) } }
            )
        }
    }
}
