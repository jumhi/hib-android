@file:Suppress("DEPRECATION")
package com.sameerasw.airsync.presentation.ui.components

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.sameerasw.airsync.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AboutSection(
    modifier: Modifier = Modifier,
    onAvatarLongClick: () -> Unit = {},
    onAvatarLongClickWithPosition: ((Offset) -> Unit)? = null,
    appName: String = "H.I.B",
    developerName: String = stringResource(R.string.hib_maker_name),
    description: String = "H.I.B enables seamless synchronization between your Android device and your computers (Mac and Windows). Share notifications, clipboard content, and device status wirelessly over your local network."
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var showAppLogo by remember { mutableStateOf(false) }
    var avatarCenterOffset by remember { mutableStateOf(Offset.Zero) }

    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) {
        "Unknown"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceBright
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "$appName v$versionName",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedContent(
                targetState = showAppLogo,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "AvatarLogoToggle"
            ) { isLogoVisible ->
                if (isLogoVisible) {
                    RotatingAppIcon(
                        haptics = haptics,
                        modifier = Modifier
                            .size(200.dp)
                            .onGloballyPositioned { coords ->
                                val pos = coords.positionInRoot()
                                val size = coords.size
                                avatarCenterOffset = Offset(
                                    x = pos.x + (size.width / 2f),
                                    y = pos.y + (size.height / 2f)
                                )
                            }
                            .combinedClickable(
                                onClick = { showAppLogo = false },
                                onLongClick = {
                                    onAvatarLongClick()
                                    onAvatarLongClickWithPosition?.invoke(avatarCenterOffset)
                                }
                            ),
                        isVisible = true
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.hibbit),
                        contentDescription = "H.I.B mascot",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(120.dp)
                            .onGloballyPositioned { coords ->
                                val pos = coords.positionInRoot()
                                val size = coords.size
                                avatarCenterOffset = Offset(
                                    x = pos.x + (size.width / 2f),
                                    y = pos.y + (size.height / 2f)
                                )
                            }
                            .clip(RoundedCornerShape(32.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .combinedClickable(
                                onClick = { showAppLogo = true },
                                onLongClick = {
                                    onAvatarLongClick()
                                    onAvatarLongClickWithPosition?.invoke(avatarCenterOffset)
                                }
                            )
                    )
                }
            }

            Text(
                text = "Made by $developerName",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("H.I.B — Haitham Is Bored", fontWeight = FontWeight.Bold)
                    Text(
                        "A private Galaxy + Mac + Windows ecosystem for notifications, files, links, text, and screenshots.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    iconRes: Int,
    onClick: () -> Unit,
    outlined: Boolean = false
) {
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.padding(horizontal = 4.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = Modifier.padding(horizontal = 4.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun OtherAppButton(
    text: String,
    iconRes: Int,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 4.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}
