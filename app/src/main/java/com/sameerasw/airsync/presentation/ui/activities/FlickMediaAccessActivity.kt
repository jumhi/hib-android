package com.sameerasw.airsync.presentation.ui.activities

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.multidesk.HibCyan
import com.sameerasw.airsync.multidesk.HibInk
import com.sameerasw.airsync.multidesk.HibPanel
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import com.sameerasw.airsync.utils.RecentMedia
import com.sameerasw.airsync.widget.FlickWidget
import com.sameerasw.airsync.widget.FlickWidgetMediaJob

/**
 * H.I.B — asks, once and kindly, for photo access so the Flick widget and tile can show and flick the latest
 * photos and screenshots. Opened by the widget's first tap. Without it everything still works through the
 * photo picker; when the phone won't ask again, the button opens H.I.B's app settings instead.
 */
class FlickMediaAccessActivity : ComponentActivity() {

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, FlickMediaAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private var askedOnce by mutableStateOf(false)

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        refreshWidgets()
        if (RecentMedia.canRead(this)) finish()
    }

    private fun refreshWidgets() {
        FlickWidget.updateAll(this)
        FlickWidgetMediaJob.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        // Back from the app's settings with access: done.
        if (askedOnce && RecentMedia.canRead(this)) {
            refreshWidgets()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        if (RecentMedia.canRead(this)) {
            refreshWidgets()
            finish()
            return
        }
        setContent {
            AirSyncTheme(darkTheme = true) {
                val blocked = askedOnce && !RecentMedia.permissions().any { shouldShowRequestPermissionRationale(it) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(remember { MutableInteractionSource() }, null) { finish() },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(12.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(HibInk)
                            .clickable(remember { MutableInteractionSource() }, null) {}
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier.size(56.dp).clip(CircleShape).background(HibPanel),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = HibCyan, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Your latest photos, one tap away",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The Flick widget shows your newest photos, videos and screenshots so you can throw " +
                                "them at your computer without opening anything. They stay on your phone until " +
                                "you flick them.",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { finish() }) {
                                Text("Not now", color = Color.White.copy(alpha = 0.8f))
                            }
                            Button(
                                onClick = {
                                    if (blocked) {
                                        startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    } else {
                                        request.launch(RecentMedia.permissions())
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = HibCyan, contentColor = HibInk)
                            ) {
                                Text(if (blocked) "Open settings" else "Allow photos", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}
