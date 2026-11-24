package com.sameerasw.airsync.presentation.ui.components.cards

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.presentation.ui.activities.FlickActivity
import com.sameerasw.airsync.presentation.ui.activities.FlickSettingsActivity
import com.sameerasw.airsync.utils.HapticUtil

/**
 * H.I.B — Flick entry on the main screen. Anything can be flicked: photos and videos (system photo
 * picker, no permission needed), any file (system file picker), or what's on the clipboard (text, a link,
 * or a copied file/image). Each opens the Flick screen; point the phone at a screen and throw. Several picks
 * are one bundle, thrown together (SPACE.md §14). The tune button opens Flick settings (Point & Flick).
 */
@Composable
fun FlickCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val media = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris: List<Uri> -> openFlick(context, uris) }

    val files = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> openFlick(context, uris) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.SwipeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp).size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Flick",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalIconButton(
                    onClick = {
                        HapticUtil.performClick(haptics)
                        context.startActivity(Intent(context, FlickSettingsActivity::class.java))
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Rounded.Tune, contentDescription = "Flick settings", modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlickSourceButton(Icons.Rounded.PhotoLibrary, "Photos & videos", Modifier.weight(1f)) {
                    HapticUtil.performClick(haptics)
                    media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                FlickSourceButton(Icons.Rounded.FolderOpen, "Files", Modifier.weight(1f)) {
                    HapticUtil.performClick(haptics)
                    try {
                        files.launch(arrayOf("*/*"))
                    } catch (_: Exception) {
                        Toast.makeText(context, "No file picker on this phone", Toast.LENGTH_SHORT).show()
                    }
                }
                FlickSourceButton(Icons.Rounded.ContentPaste, "Clipboard", Modifier.weight(1f)) {
                    HapticUtil.performClick(haptics)
                    flickClipboard(context)
                }
            }
            Text(
                text = "Point your phone at a screen and flick it up. Pick several and one flick throws them all. " +
                    "Also in every app's Share menu, the Flick tile and the Flick widget.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}

@Composable
private fun FlickSourceButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
        modifier = modifier.height(72.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun openFlick(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    val clip = ClipData.newRawUri("flick", uris.first()).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    val intent = Intent(context, FlickActivity::class.java).apply {
        action = Intent.ACTION_SEND_MULTIPLE
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
}

/** Flicks what's on the clipboard: a copied file or image, else the text (a link becomes a link card). */
private fun flickClipboard(context: Context) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val clip = try { cm?.primaryClip } catch (_: Exception) { null }
    if (clip == null || clip.itemCount == 0) {
        Toast.makeText(context, "The clipboard is empty", Toast.LENGTH_SHORT).show()
        return
    }
    val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    if (uris.isNotEmpty()) {
        openFlick(context, uris)
        return
    }
    val text = (0 until clip.itemCount)
        .mapNotNull { clip.getItemAt(it).coerceToText(context)?.toString() }
        .joinToString("\n")
        .trim()
    if (text.isEmpty()) {
        Toast.makeText(context, "The clipboard is empty", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(context, FlickActivity::class.java).apply {
        action = Intent.ACTION_SEND
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(intent)
}
