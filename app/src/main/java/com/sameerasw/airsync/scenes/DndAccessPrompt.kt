package com.sameerasw.airsync.scenes

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Main-screen prompt for Do Not Disturb access (needed by Scenes to silence the phone).
 * Android lists this under "special access", not normal app permissions, so it's easy to miss.
 * Shown only while access is missing; re-checks whenever the user comes back to H.I.B.
 */
@Composable
fun DndAccessPrompt(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ScenePhoneActions.hasPolicyAccess(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = ScenePhoneActions.hasPolicyAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (granted) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Do Not Disturb access", style = MaterialTheme.typography.titleSmall)
            Text(
                "Lets Mac Scenes silence this phone (e.g. during meetings). Turn on H.I.B on the next screen.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    val attempts = buildList {
                        // Android 15+: jump straight to H.I.B's own toggle.
                        if (Build.VERSION.SDK_INT >= 35) {
                            add(Intent("android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS")
                                .setData(Uri.parse("package:${context.packageName}")))
                        }
                        add(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${context.packageName}")))
                    }
                    for (intent in attempts) {
                        if (runCatching { context.startActivity(intent.addFlags(flags)) }.isSuccess) break
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Allow Do Not Disturb") }
        }
    }
}
