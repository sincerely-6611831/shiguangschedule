package com.xingheyuzhuan.shiguangschedule.widget.keepalive

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.xingheyuzhuan.shiguangschedule.BuildConfig
import com.xingheyuzhuan.shiguangschedule.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import rikka.shizuku.Shizuku

class WidgetKeepAliveActivity : AppCompatActivity() {
    private var enabled by mutableStateOf(false)
    private var overlayEnabled by mutableStateOf(false)
    private var authorized by mutableStateOf(false)
    private var shizukuAvailable by mutableStateOf(false)
    private var batteryExempt by mutableStateOf(false)
    private var hasBackup by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refreshStatus() }
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == PERMISSION_REQUEST) runOnUiThread {
            refreshStatus()
            if (result != PackageManager.PERMISSION_GRANTED) message = getString(R.string.widget_shizuku_denied)
        }
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshStatus() } }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread { refreshStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WidgetKeepAliveService.preferences(this).registerOnSharedPreferenceChangeListener(preferenceListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                        if (it) toggleService(true) else message = getString(R.string.widget_notification_denied)
                    }
                    Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { finish() }) { Text(stringResource(R.string.widget_keepalive_back)) }
                        Text(stringResource(R.string.widget_keepalive_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.widget_keepalive_description))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.widget_keepalive_foreground), Modifier.weight(1f))
                            Switch(checked = enabled, onCheckedChange = {
                                if (it && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                    this@WidgetKeepAliveActivity, Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED) {
                                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else toggleService(it)
                            })
                        }
                        Text(stringResource(if (WidgetKeepAliveService.isRunning)
                            R.string.widget_service_running else R.string.widget_service_stopped))
                        Text(stringResource(R.string.widget_keepalive_overlay_description))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.widget_keepalive_overlay), Modifier.weight(1f))
                            Switch(checked = overlayEnabled, enabled = enabled, onCheckedChange = { useOverlay ->
                                if (useOverlay && !Settings.canDrawOverlays(this@WidgetKeepAliveActivity)) {
                                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                                } else {
                                    WidgetKeepAliveService.setOverlayEnabled(this@WidgetKeepAliveActivity, useOverlay)
                                    refreshStatus()
                                }
                            })
                        }
                        Text(stringResource(if (batteryExempt) R.string.widget_battery_exempt else R.string.widget_battery_restricted))
                        Text(stringResource(if (WidgetKeepAliveService.isOverlayAttached)
                            R.string.widget_overlay_running else R.string.widget_overlay_stopped))
                        OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                            Text(stringResource(R.string.widget_battery_settings))
                        }
                        OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName"))) }) { Text(stringResource(R.string.widget_autostart_settings)) }
                        HorizontalDivider()
                        Text(stringResource(when {
                            !shizukuAvailable -> R.string.widget_shizuku_unavailable
                            authorized -> R.string.widget_shizuku_authorized
                            else -> R.string.widget_shizuku_unauthorized
                        }))
                        Text(stringResource(R.string.widget_shizuku_description))
                        OutlinedButton(enabled = !busy && !authorized, onClick = {
                            try {
                                if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(PERMISSION_REQUEST)
                                else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                            } catch (e: RuntimeException) { message = e.message.orEmpty() }
                        }) { Text(stringResource(R.string.widget_shizuku_authorize)) }
                        Button(enabled = authorized && !busy, onClick = { changePolicy(true) }) {
                            Text(stringResource(R.string.widget_shizuku_apply))
                        }
                        OutlinedButton(enabled = authorized && hasBackup && !busy, onClick = { changePolicy(false) }) {
                            Text(stringResource(R.string.widget_shizuku_restore))
                        }
                        Text(stringResource(R.string.widget_keepalive_limits))
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (message.isNotEmpty()) Text(message)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WidgetKeepAliveService.startIfEnabled(this)
        refreshStatus()
    }

    override fun onDestroy() {
        WidgetKeepAliveService.preferences(this).unregisterOnSharedPreferenceChangeListener(preferenceListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        super.onDestroy()
    }

    private fun refreshStatus() {
        enabled = WidgetKeepAliveService.isEnabled(this)
        overlayEnabled = WidgetKeepAliveService.isOverlayEnabled(this) && Settings.canDrawOverlays(this)
        shizukuAvailable = Shizuku.pingBinder() && !Shizuku.isPreV11()
        authorized = shizukuAvailable && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        hasBackup = WidgetKeepAliveService.preferences(this).contains(POLICY_BACKUP)
    }

    private fun toggleService(value: Boolean) {
        try {
            WidgetKeepAliveService.setEnabled(this, value)
        } catch (e: RuntimeException) { message = e.message.orEmpty() }
        refreshStatus()
    }

    private fun changePolicy(enable: Boolean) {
        busy = true
        lifecycleScope.launch {
            val userId = Process.myUid() / 100000
            val args = Shizuku.UserServiceArgs(ComponentName(this@WidgetKeepAliveActivity, BackgroundPolicyService::class.java))
                .daemon(false).tag("widget_policy_$userId").processNameSuffix("widget_policy")
                .version(BuildConfig.VERSION_CODE).debuggable(BuildConfig.DEBUG)
            val ready = CompletableDeferred<IBackgroundPolicyService>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    ready.complete(IBackgroundPolicyService.Stub.asInterface(binder))
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    ready.completeExceptionally(IllegalStateException(getString(R.string.widget_shizuku_disconnected)))
                }
            }
            try {
                Shizuku.bindUserService(args, connection)
                val service = withTimeout(15000) { ready.await() }
                withContext(Dispatchers.IO) {
                    val preferences = WidgetKeepAliveService.preferences(this@WidgetKeepAliveActivity)
                    if (enable && !preferences.contains(POLICY_BACKUP)) {
                        // 先持久化原策略；即使只应用了一部分，也可再次点击恢复。
                        check(preferences.edit().putString(POLICY_BACKUP, service.readPolicy(userId)).commit())
                    }
                    val policy = if (enable) JSONObject().apply {
                        put("whitelisted", true)
                        put("RUN_IN_BACKGROUND", "allow")
                        put("RUN_ANY_IN_BACKGROUND", "allow")
                    }.toString() else checkNotNull(preferences.getString(POLICY_BACKUP, null))
                    service.applyPolicy(userId, policy)
                    if (!enable) check(preferences.edit().remove(POLICY_BACKUP).commit())
                }
                message = getString(if (enable) R.string.widget_shizuku_applied else R.string.widget_shizuku_restored)
            } catch (e: TimeoutCancellationException) {
                message = getString(R.string.widget_shizuku_timeout)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = getString(R.string.widget_shizuku_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
                busy = false
                refreshStatus()
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST = 4103
        private const val POLICY_BACKUP = "original_background_policy"
    }
}
