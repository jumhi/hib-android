package com.sameerasw.airsync.multidesk

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sameerasw.airsync.AdbDiscoveryHolder
import com.sameerasw.airsync.AirSyncApp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.AdbMdnsDiscovery
import com.sameerasw.airsync.utils.PermissionUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.I.B ADB setup without a terminal (docs/SPACE.md §8), phone side.
 *
 * Tells every connected computer (`adbSetupState`) whether Developer options and Wireless debugging are
 * on and which ports the phone's own Wireless debugging uses, read from the phone itself (settings +
 * NSD for its own services), so the computer never has to find the phone with mDNS across subnets.
 * Sent after each handshake, on every change, and in reply to `adbSetupRequest`; while a computer's
 * setup is open (`watch`) the pairing service is watched too and changes go out within ~1 s.
 */
object AdbSetupMonitor {
    private const val TAG = "AdbSetup"
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val REPORT_DEBOUNCE_MS = 250L
    private const val WATCH_TICK_MS = 1_000L
    private const val AFTER_HANDSHAKE_MS = 2_000L
    private const val CONNECT_RESTART_MS = 15_000L
    private const val LOCAL_ADDR_TTL_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val ledger = AdbStateLedger()
    private val watch = AdbWatch()
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var pairing: AdbMdnsDiscovery? = null
    private var watchJob: Job? = null
    @Volatile private var watchStartedAt = 0L
    @Volatile private var lastPairingRetry = 0L
    @Volatile private var lastConnectRestart = 0L
    @Volatile private var localCache: Set<String> = emptySet()
    @Volatile private var localCacheAt = 0L

    private val _state = MutableStateFlow(AdbSetupState.OFF)
    /** The phone's own setup state, live (for the in-app "Set up mirroring" card). */
    val state: StateFlow<AdbSetupState> = _state.asStateFlow()

    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!started.compareAndSet(false, true)) return
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = onSettingsChanged()
        }
        listOf(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, ADB_WIFI_ENABLED).forEach { key ->
            runCatching {
                ctx.contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
            }.onFailure { Log.w(TAG, "Can't watch $key: ${it.javaClass.simpleName}") }
        }
        AdbDiscoveryHolder.addChangeListener { trigger.trySend(Unit) }
        scope.launch {
            while (true) {
                trigger.receive()
                delay(REPORT_DEBOUNCE_MS)
                runCatching { report() }.onFailure { Log.w(TAG, "Report failed: ${it.message}") }
            }
        }
        _state.value = current(ctx)
    }

    // MARK: - readings

    fun developerOptionsOn(context: Context): Boolean =
        readGlobal(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == true

    /** null when this phone won't let apps read the setting. */
    private fun readGlobal(context: Context, key: String): Boolean? = try {
        Settings.Global.getInt(context.contentResolver, key, 0) != 0
    } catch (_: Exception) {
        null
    }

    private fun localAddresses(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - localCacheAt < LOCAL_ADDR_TTL_MS && localCache.isNotEmpty()) return localCache
        val set = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .flatMap { it.inetAddresses.toList() }
                .mapNotNull { it.hostAddress?.let(AdbOwnService::normalize) }
                .toSet()
        }.getOrDefault(emptySet())
        localCache = set
        localCacheAt = now
        return set
    }

    private fun services(list: List<AdbMdnsDiscovery.AdbServiceInfo>) =
        list.map { AdbService(it.serviceName, it.port, it.hostAddresses) }

    /** Reads everything now. */
    fun current(context: Context): AdbSetupState {
        val local = localAddresses()
        val connect = AdbOwnService.pick(services(AdbDiscoveryHolder.getDiscoveredServices()), local)
        val pair = AdbOwnService.pick(
            services(pairing?.getDiscoveredServices().orEmpty()), local, ownName = connect?.name
        )
        return AdbSetupState.build(
            developerOptions = developerOptionsOn(context),
            wirelessSetting = readGlobal(context, ADB_WIFI_ENABLED),
            connect = connect,
            pairing = pair
        )
    }

    /** Re-reads the settings for the UI (e.g. when the user comes back from Settings). */
    fun refresh(context: Context) {
        start(context)
        _state.value = current(context.applicationContext)
        trigger.trySend(Unit)
    }

    private fun onSettingsChanged() {
        val ctx = appContext ?: return
        scope.launch {
            val s = current(ctx)
            _state.value = s
            AdbSetupNotifications.cancelSatisfied(ctx, s)
            if (s.wirelessDebugging) ensureConnectDiscovery(ctx)
            trigger.trySend(Unit)
        }
    }

    // MARK: - reporting

    /** Sends the state to every connected computer that hasn't been told this exact state. */
    @Synchronized
    private fun report() {
        val ctx = appContext ?: return
        val s = current(ctx)
        _state.value = s
        AdbSetupNotifications.cancelSatisfied(ctx, s)
        val json = AdbSetupJson.state(s)
        DesktopHub.connectedLinks().forEach { link ->
            if (ledger.needs(link.id, s) && link.send(json)) {
                ledger.record(link.id, s)
                Log.d(TAG, "adbSetupState → ${link.name}: dev=${s.developerOptions} wifi=${s.wirelessDebugging} " +
                    "connect=${s.connectPort} pairing=${s.pairingPort != null}")
            }
        }
    }

    @Synchronized
    private fun sendTo(link: DesktopLink) {
        val ctx = appContext ?: return
        if (!link.isReady()) return
        val s = current(ctx)
        _state.value = s
        if (link.send(AdbSetupJson.state(s))) ledger.record(link.id, s)
    }

    // MARK: - computers

    /** A computer finished its handshake: it gets the state shortly after (behind its device info). */
    fun onComputerConnected(link: DesktopLink) {
        val ctx = appContext ?: return
        ledger.forget(link.id)
        watch.stop(link.id)
        if (readGlobal(ctx, ADB_WIFI_ENABLED) != false) ensureConnectDiscovery(ctx)
        scope.launch {
            delay(AFTER_HANDSHAKE_MS)
            sendTo(link)
        }
    }

    fun onComputerGone(id: String) {
        ledger.forget(id)
        watch.stop(id)
        updateWatch()
    }

    /** `adbSetupRequest` from [link] (SPACE.md §8.2). */
    fun onRequest(context: Context, link: DesktopLink, json: String) {
        start(context)
        val req = AdbSetupJson.parseRequest(json) ?: return
        when (req.watch) {
            true -> watch.renew(link.id)
            false -> watch.stop(link.id)
            null -> {}
        }
        updateWatch()
        req.open?.let { open(context, it, link.name) }
        scope.launch { sendTo(link) }
    }

    // MARK: - watch (fast reporting + pairing discovery)

    @Synchronized
    private fun updateWatch() {
        val ctx = appContext ?: return
        if (watch.isActive()) {
            ensureConnectDiscovery(ctx)
            startPairingDiscovery(ctx)
            if (watchJob == null) {
                watchStartedAt = System.currentTimeMillis()
                watchJob = scope.launch { watchLoop() }
            }
        } else {
            watchJob?.cancel()
            watchJob = null
            stopPairingDiscovery()
        }
    }

    private suspend fun watchLoop() {
        while (true) {
            delay(WATCH_TICK_MS)
            val keepGoing = synchronized(this) {
                if (watch.isActive()) {
                    true
                } else {
                    watchJob = null
                    stopPairingDiscovery()
                    false
                }
            }
            if (!keepGoing) {
                trigger.trySend(Unit)
                return
            }
            val ctx = appContext ?: continue
            runCatching {
                report()
                // NSD can miss its own service after Wireless debugging was toggled: look again now and then.
                val s = _state.value
                val now = System.currentTimeMillis()
                if (s.wirelessDebugging && s.connectPort == null && now - watchStartedAt > 4_000L &&
                    now - lastConnectRestart > CONNECT_RESTART_MS
                ) {
                    lastConnectRestart = now
                    if (PermissionUtil.isLocalNetworkPermissionGranted(ctx)) AdbDiscoveryHolder.restartDiscovery(ctx)
                }
                // Pairing discovery failed to start (e.g. Wi-Fi was off): retry every few seconds.
                if (pairing?.isRunning() != true && now - lastPairingRetry > 5_000L) {
                    lastPairingRetry = now
                    synchronized(this) { if (watchJob != null) startPairingDiscovery(ctx) }
                }
            }
        }
    }

    private fun ensureConnectDiscovery(ctx: Context) {
        if (!PermissionUtil.isLocalNetworkPermissionGranted(ctx)) return
        if (AdbDiscoveryHolder.isRunning()) return
        if (AdbDiscoveryHolder.isDiscoveryActive()) {
            // Created but not running (start failed): try again, not more often than the watch retry.
            val now = System.currentTimeMillis()
            if (now - lastConnectRestart < 3_000L) return
            lastConnectRestart = now
            AdbDiscoveryHolder.restartDiscovery(ctx)
        } else {
            AdbDiscoveryHolder.initialize(ctx)
        }
    }

    @Synchronized
    private fun startPairingDiscovery(ctx: Context) {
        if (!PermissionUtil.isLocalNetworkPermissionGranted(ctx)) return
        val d = pairing ?: AdbMdnsDiscovery(ctx, AdbMdnsDiscovery.PAIRING_SERVICE) { trigger.trySend(Unit) }
            .also { pairing = it }
        if (!d.isRunning()) d.startDiscovery()
    }

    @Synchronized
    private fun stopPairingDiscovery() {
        pairing?.stopDiscovery()
        pairing = null
    }

    // MARK: - opening the right screen

    /**
     * Opens [which] on the phone for a computer's request. Android blocks activity starts from the
     * background, so unless H.I.B is on screen this posts a heads-up notification that opens it.
     */
    fun open(context: Context, which: AdbSetupOpen, computer: String?) {
        val ctx = context.applicationContext
        val canStart = AirSyncApp.isAppForeground() || runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)
        if (!canStart) {
            AdbSetupNotifications.show(ctx, which, computer)
            return
        }
        main.post {
            try {
                ctx.startActivity(AdbSetupLaunchActivity.intent(ctx, which))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open setup screen: ${e.javaClass.simpleName}")
                AdbSetupNotifications.show(ctx, which, computer)
            }
        }
    }
}

/** The Settings screens for each step, with the hint toast. Call from an activity (or with NEW_TASK). */
object AdbSetupScreens {
    private const val FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
    private const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    private const val WIRELESS_KEY = "toggle_adb_wireless"

    val isSamsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** Where Build number is, in this phone's words. */
    val buildNumberPath: String
        get() = if (isSamsung) "Settings → About phone → Software information" else "Settings → About phone"

    /**
     * Opens the screen for [requested]. Wireless debugging lives inside Developer options, so it falls back
     * to the Developer options step while those are off; the Developer options step moves on to Wireless
     * debugging once they're on.
     */
    fun launch(context: Context, requested: AdbSetupOpen) {
        val devOn = AdbSetupMonitor.developerOptionsOn(context)
        when {
            requested == AdbSetupOpen.WIRELESS && devOn -> openWireless(context)
            requested == AdbSetupOpen.DEVELOPER && devOn -> openWireless(context)
            else -> openDeveloperHelp(context)
        }
    }

    private fun openDeveloperHelp(context: Context) {
        val attempts = buildList {
            if (isSamsung) {
                add(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$SoftwareInfoSettingsActivity")))
            }
            add(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        val opened = startFirst(context, attempts)
        val hint = when {
            isSamsung && opened == 0 -> "Tap Build number 7 times"
            isSamsung -> "Open Software information, then tap Build number 7 times"
            opened == 0 -> "Tap Build number 7 times"
            else -> "Open About phone, then tap Build number 7 times"
        }
        // Two long toasts: time to scroll down to Build number.
        toast(context, hint)
        toast(context, hint)
    }

    private fun openWireless(context: Context) {
        val args = Bundle().apply { putString(FRAGMENT_ARGS_KEY, WIRELESS_KEY) }
        val dev = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(FRAGMENT_ARGS_KEY, WIRELESS_KEY)
            .putExtra(SHOW_FRAGMENT_ARGS, args)
        startFirst(context, listOf(dev, Intent(Settings.ACTION_SETTINGS)))
        toast(context, "Turn on Wireless debugging, then tap Allow")
    }

    /** Index of the intent that opened, or -1. */
    private fun startFirst(context: Context, intents: List<Intent>): Int {
        intents.forEachIndexed { i, intent ->
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return i
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            } catch (e: Exception) {
                Log.w("AdbSetup", "Couldn't open ${intent.action ?: intent.component?.className}: ${e.javaClass.simpleName}")
            }
        }
        return -1
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
    }
}

/** Heads-up notifications that open a setup screen when the app can't open it from the background. */
object AdbSetupNotifications {
    const val CHANNEL_ID = "hib_adb_setup"
    private const val ID_DEVELOPER = 0x4842_4401
    private const val ID_WIRELESS = 0x4842_4402
    @Volatile private var shown = emptySet<Int>()

    fun idFor(which: AdbSetupOpen) = if (which == AdbSetupOpen.DEVELOPER) ID_DEVELOPER else ID_WIRELESS

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Set up mirroring", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Opens Developer options or Wireless debugging when your computer asks"
                setShowBadge(false)
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun show(context: Context, requested: AdbSetupOpen, computer: String?) {
        ensureChannel(context)
        val which = if (requested == AdbSetupOpen.WIRELESS && !AdbSetupMonitor.developerOptionsOn(context)) {
            AdbSetupOpen.DEVELOPER
        } else requested
        val id = idFor(which)
        val (title, text) = when (which) {
            AdbSetupOpen.DEVELOPER ->
                (if (AdbSetupScreens.isSamsung) "Tap to open Software information" else "Tap to open About phone") to
                    "Then tap Build number 7 times to turn on Developer options."
            AdbSetupOpen.WIRELESS ->
                "Tap to open Wireless debugging" to "Turn it on, then tap Allow when the phone asks."
        }
        val open = PendingIntent.getActivity(
            context, id, AdbSetupLaunchActivity.intent(context, which, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(if (computer.isNullOrBlank()) "Set up mirroring" else "Set up mirroring · $computer")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(AdbWatch.WINDOW_MS)
            .setContentIntent(open)
        try {
            val nm = NotificationManagerCompat.from(context)
            // One step at a time.
            nm.cancel(idFor(if (which == AdbSetupOpen.DEVELOPER) AdbSetupOpen.WIRELESS else AdbSetupOpen.DEVELOPER))
            nm.notify(id, builder.build())
            shown = shown + id
        } catch (e: SecurityException) {
            Log.w("AdbSetup", "Notifications not allowed: ${e.message}")
        }
    }

    /** Clears a step's notification once that step is done. */
    fun cancelSatisfied(context: Context, s: AdbSetupState) {
        if (shown.isEmpty()) return
        val done = buildSet {
            if (s.developerOptions) add(ID_DEVELOPER)
            if (s.wirelessDebugging) add(ID_WIRELESS)
        }.intersect(shown)
        if (done.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        done.forEach { runCatching { nm.cancel(it) } }
        shown = shown - done
    }

    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
        shown = shown - id
    }
}

/**
 * Opens a setup screen from a notification tap or a computer's request while H.I.B is on screen, and
 * shows the hint ("Tap Build number 7 times") over it. No UI of its own.
 */
class AdbSetupLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val which = AdbSetupOpen.fromWire(intent.getStringExtra(EXTRA_OPEN)) ?: AdbSetupOpen.WIRELESS
        val notif = intent.getIntExtra(EXTRA_NOTIF, 0)
        if (notif != 0) AdbSetupNotifications.cancel(this, notif)
        AdbSetupScreens.launch(this, which)
        finish()
    }

    companion object {
        private const val EXTRA_OPEN = "hib_adb_open"
        private const val EXTRA_NOTIF = "hib_adb_notif"

        fun intent(context: Context, which: AdbSetupOpen, notifId: Int = 0): Intent =
            Intent(context, AdbSetupLaunchActivity::class.java).apply {
                putExtra(EXTRA_OPEN, which.wire)
                if (notifId != 0) putExtra(EXTRA_NOTIF, notifId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
    }
}
