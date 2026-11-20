package com.sameerasw.airsync.presentation.ui.components

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.sameerasw.airsync.R
import com.sameerasw.airsync.presentation.ui.components.cards.MirroringSetupCard
import com.sameerasw.airsync.presentation.ui.components.sheets.HelpSection

@Composable
fun HelpAndGuidesContent() {
    val sections = remember {
        listOf(
            HelpSection(
                title = "Getting Started",
                iconRes = R.drawable.rounded_qr_code_scanner_24,
                content = "To connect, keep both devices on the same Wi-Fi network. Scan the QR code shown by H.I.B on your Mac or Windows PC.",
                links = emptyList()
            ),
            HelpSection(
                title = "Permissions & Usage",
                iconRes = R.drawable.rounded_security_24,
                content = "• Notification Access: Required to sync alerts/media. For sideloaded installs, enable 'Restricted Settings' in App Info.\n• Post Notifications: For the ongoing connection indicator.\n• Background Usage: Keeps the connection alive.\n• Storage: Required for wallpaper sync.\n• Location & Phone State: Required to sync cellular network status to your computers.",
                links = emptyList()
            ),
            HelpSection(
                title = "ADB & Mirroring",
                iconRes = R.drawable.rounded_laptop_mac_24,
                content = "Mirroring, Play on computer and opening this phone's apps on your computer use Wireless debugging. Set it up once with the steps below; your computer brings everything it needs, so there is nothing to install and no terminal.",
                links = emptyList(),
                extra = { MirroringSetupCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) }
            ),
            HelpSection(
                title = "Re-connection",
                iconRes = R.drawable.rounded_sync_desktop_24,
                content = "Auto-reconnect tries for 1 minute (every 10s) and then every minute. Use the Quick Settings tile for instant one-tap reconnection to the last device.",
            ),
            HelpSection(
                title = "Updates",
                iconRes = R.drawable.rounded_notifications_active_24,
                content = "Install new H.I.B builds on your phone when they are available. The macOS app has a built-in updater.",
            ),
            HelpSection(
                title = "Troubleshooting",
                iconRes = R.drawable.rounded_troubleshoot_24,
                content = "• IP N/A on the computer: Ensure LAN connection (not just internet).\n• Random Disconnects: Check battery/data saving policies and network stability.\n• Black Display: If 'Darken screen' is on during mirroring, use ⌥+Shift+O to reveal.",
                links = emptyList()
            )
        )
    }

    RoundedCardContainer {
        sections.forEach { section ->
            ExpandableHelpSection(section)
        }
    }
}

@Composable
private fun ExpandableHelpSection(section: HelpSection) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "arrow_rotation"
    )
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = if (expanded) MaterialTheme.colorScheme.surfaceBright else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = section.iconRes),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(rotation)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = section.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    section.extra?.invoke()

                    section.links.forEach { (label, url) ->
                        TextButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // Ignore
                                }
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}
