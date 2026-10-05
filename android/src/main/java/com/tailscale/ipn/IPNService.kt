// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import libtailscale.Libtailscale

open class IPNService : VpnService(), libtailscale.IPNService {
  private val TAG = "IPNService"
  private val randomID: String = UUID.randomUUID().toString()
  private lateinit var app: App
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var closed = false
  private val restartRequested = AtomicBoolean(false)
  private lateinit var powerController: ScreenPowerController
  private lateinit var powerPrefs: SharedPreferences
  private val screenReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
          if (ScreenPowerSettings.connectionRequested(this@IPNService)) powerController.update()
        }
      }
  private val powerPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (key == ScreenPowerSettings.ENABLED && ScreenPowerSettings.connectionRequested(this)) {
      powerController.update()
    }
  }

  private fun requestConnection(wanted: Boolean) {
    // Persist intent separately from WantRunning, which is false while screen-paused.
    ScreenPowerSettings.setConnectionRequested(this, wanted)
    powerController.update()
  }

  private fun shouldRun(): Boolean =
      ScreenPowerSettings.shouldRun(
          requested = ScreenPowerSettings.connectionRequested(this),
          interactive = getSystemService(PowerManager::class.java).isInteractive,
          enabled = ScreenPowerSettings.enabled(this),
          forceEnabled = MDMSettings.forceEnabled.flow.value.value,
          lockdown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLockdownEnabled,
      )

  override fun id(): String {
    return randomID
  }

  override fun updateVpnStatus(status: Boolean) {
    app.getAppScopedViewModel().setVpnActive(status)
  }

  override fun onCreate() {
    super.onCreate()
    // grab app to make sure it initializes
    app = App.get()

    powerController =
        ScreenPowerController(
            scope = scope,
            shouldRun = { shouldRun() },
            connectionRequested = { ScreenPowerSettings.connectionRequested(this) },
            applyRunning = { running -> applyPowerState(running) },
            stopIfDisconnected = {
              withContext(Dispatchers.Main) {
                // Recheck on the main thread, where start/stop intents are delivered.
                if (!ScreenPowerSettings.connectionRequested(this@IPNService)) {
                  close()
                  true
                } else false
              }
            },
        )
    powerPrefs = ScreenPowerSettings.prefs(this)
    powerPrefs.registerOnSharedPreferenceChangeListener(powerPrefsListener)
    ContextCompat.registerReceiver(
        this,
        screenReceiver,
        IntentFilter().apply {
          addAction(Intent.ACTION_SCREEN_ON)
          addAction(Intent.ACTION_SCREEN_OFF)
        },
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    scope.launch {
      MDMSettings.forceEnabled.flow.collect {
        if (ScreenPowerSettings.connectionRequested(this@IPNService)) powerController.update()
      }
    }

    NetworkChangeCallback.setUnderlyingNetworkListener { network ->
      updateUnderlyingNetwork(network)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
      when (intent?.action) {
        ACTION_STOP_VPN -> {
          requestConnection(false)
          START_NOT_STICKY
        }
        ACTION_RESTART_VPN -> {
          // Rebuild the tunnel in this service instance. Destroying and immediately
          // restarting it could race Android's lifecycle and discard screen events.
          restartRequested.set(true)
          powerController.update()
          START_STICKY
        }
        ACTION_START_FOREGROUND_ONLY -> {
          // Start the foreground service notification without creating a VPN tunnel.
          // This is used during interactive login so that Android does not freeze the process
          // or restrict network access while the user completes auth in the browser.
          showForegroundNotification()
          START_NOT_STICKY
        }
        ACTION_START_VPN -> {
          showForegroundNotification()
          requestConnection(true)
          START_STICKY
        }
        "android.net.VpnService" -> {
          // Keep screen broadcasts deliverable even while the VPN tunnel is paused.
          showForegroundNotification()
          requestConnection(true)
          START_STICKY
        }
        else -> {
          // This means that we were restarted after the service was killed
          // (potentially due to OOM).
          if (
              UninitializedApp.get().isAbleToStartVPN() &&
                  ScreenPowerSettings.connectionRequested(this)
          ) {
            showForegroundNotification()
            powerController.update()
            START_STICKY
          } else {
            START_NOT_STICKY
          }
        }
      }

  override fun close() {
    if (closed) return
    closed = true
    Notifier.setState(Ipn.State.Stopping)
    disconnectVPN()
    Libtailscale.serviceDisconnect(this)
  }

  override fun disconnectVPN() {
    stopSelf()
  }

  override fun onDestroy() {
    unregisterReceiver(screenReceiver)
    powerPrefs.unregisterOnSharedPreferenceChangeListener(powerPrefsListener)
    powerController.close()
    scope.cancel()
    NetworkChangeCallback.setUnderlyingNetworkListener(null)
    close()
    updateVpnStatus(false)
    super.onDestroy()
  }

  override fun onRevoke() {
    // VPN permission was granted to another app, so tell the Go backend and then set prepared to be
    // false so that when user attempts to connect again, VpnService.prepare() is called
    ScreenPowerSettings.setConnectionRequested(this, false)
    stopAfterPendingEdits()
    setVpnPrepared(false)
    close()
    updateVpnStatus(false)
    super.onRevoke()
  }

  private suspend fun editRunning(running: Boolean): Boolean {
    val result =
        suspendCancellableCoroutine<Result<Ipn.Prefs>> { continuation ->
          Client(scope).editPrefs(Ipn.MaskedPrefs().apply { WantRunning = running }) {
            if (continuation.isActive) continuation.resume(it)
          }
        }
    result.onFailure { TSLog.e(TAG, "Screen power update failed: $it") }
    return result.isSuccess
  }

  private suspend fun applyPowerState(running: Boolean): Boolean {
    if (restartRequested.getAndSet(false)) {
      if (!editRunning(false)) {
        restartRequested.set(true)
        return false
      }
      Libtailscale.serviceDisconnect(this)
    }
    // Keep the foreground service and receiver during screen-off. Restoring the
    // same service after a pause avoids Android background-start restrictions.
    if (running) Libtailscale.requestVPN(this)
    return editRunning(running)
  }

  private fun stopAfterPendingEdits(onSuccess: (() -> Unit)? = null) {
    scope.cancel()
    app.applicationScope.launch {
      // Go LocalAPI calls can finish after coroutine cancellation. Wait for the
      // service's children before the final stop edit so that it cannot be undone.
      scope.coroutineContext[Job]?.join()
      app.setWantRunning(false, onSuccess)
    }
  }

  private fun setVpnPrepared(isPrepared: Boolean) {
    app.getAppScopedViewModel().setVpnPrepared(isPrepared)
  }

  private fun updateUnderlyingNetwork(network: Network?) {
    val networks = network?.let { arrayOf(it) } ?: emptyArray()
    if (!setUnderlyingNetworks(networks)) {
      TSLog.w(TAG, "Failed to set underlying network: $network")
    } else {
      TSLog.d(TAG, "Set underlying network: $network")
    }
  }

  private fun showForegroundNotification(
      hideDisconnectAction: Boolean,
      exitNodeName: String? = null,
  ) {
    try {
      startForeground(
          UninitializedApp.STATUS_NOTIFICATION_ID,
          UninitializedApp.get().buildStatusNotification(true, hideDisconnectAction, exitNodeName),
      )
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to start foreground service: $e")
    }
  }

  private fun showForegroundNotification() {
    val hideDisconnectAction = MDMSettings.forceEnabled.flow.value.value
    val exitNodeName = UninitializedApp.getExitNodeName(Notifier.prefs.value, Notifier.netmap.value)
    showForegroundNotification(hideDisconnectAction, exitNodeName)
  }

  private fun configIntent(): PendingIntent {
    return PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun allowApp(b: Builder, name: String) {
    try {
      b.addAllowedApplication(name)
    } catch (e: PackageManager.NameNotFoundException) {
      TSLog.e(TAG, "Failed to add allowed application: $e")
    }
  }

  private fun disallowApp(b: Builder, name: String) {
    try {
      b.addDisallowedApplication(name)
    } catch (e: PackageManager.NameNotFoundException) {
      TSLog.e(TAG, "Failed to add disallowed application: $e")
    }
  }

  override fun newBuilder(): VPNServiceBuilder {
    val b: Builder =
        Builder()
            .setConfigureIntent(configIntent())
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      b.setMetered(false) // Inherit the metered status from the underlying networks.
    }

    val underlyingNetwork = NetworkChangeCallback.cachedDefaultNetwork
    b.setUnderlyingNetworks(underlyingNetwork?.let { arrayOf(it) } ?: emptyArray())

    val mdmAllowed =
        MDMSettings.includedPackages.flow.value.value?.split(",")?.map { it.trim() } ?: emptyList()
    val mdmDisallowed =
        MDMSettings.excludedPackages.flow.value.value?.split(",")?.map { it.trim() } ?: emptyList()

    var packagesList: List<String>
    var allowPackages: Boolean
    if (mdmAllowed.isNotEmpty()) {
      // An admin defined a list of packages that are exclusively allowed to be used via
      // Tailscale, so only allow those.
      packagesList = mdmAllowed
      allowPackages = true
      TSLog.d(TAG, "Included application packages were set via MDM: $mdmAllowed")
    } else if (mdmDisallowed.isNotEmpty()) {
      // An admin defined a list of packages that are excluded from accessing Tailscale,
      // so ignore user definitions and only exclude those
      packagesList = mdmDisallowed
      allowPackages = false
      TSLog.d(TAG, "Excluded application packages were set via MDM: $mdmDisallowed")
    } else {
      // Otherwise, prevent user manually disallowed apps from getting their traffic + DNS routed
      // via Tailscale
      packagesList = UninitializedApp.get().selectedPackageNames()
      allowPackages = UninitializedApp.get().allowSelectedPackages()
      TSLog.d(TAG, "Application packages were set by user: $packagesList")
    }

    packagesList =
        packagesForVpnBuilder(
            packagesList = packagesList,
            allowPackages = allowPackages,
            tailscalePackageName = UninitializedApp.get().packageName,
            builtInDisallowedPackages = UninitializedApp.get().builtInDisallowedPackageNames,
        )

    if (allowPackages) {
      for (packageName in packagesList) {
        TSLog.d(TAG, "Including app: $packageName")
        allowApp(b, packageName)
      }
    } else {
      for (packageName in packagesList) {
        TSLog.d(TAG, "Disallowing app: $packageName")
        disallowApp(b, packageName)
      }
    }

    return VPNServiceBuilder(b)
  }

  companion object {
    const val ACTION_START_VPN = "com.tailscale.ipn.START_VPN"
    const val ACTION_STOP_VPN = "com.tailscale.ipn.STOP_VPN"
    const val ACTION_RESTART_VPN = "com.tailscale.ipn.RESTART_VPN"
    const val ACTION_START_FOREGROUND_ONLY = "com.tailscale.ipn.START_FOREGROUND_ONLY"
  }
}

internal fun packagesForVpnBuilder(
    packagesList: List<String>,
    allowPackages: Boolean,
    tailscalePackageName: String,
    builtInDisallowedPackages: List<String>,
): List<String> =
    if (allowPackages) {
      if (packagesList.isEmpty()) {
        emptyList()
      } else {
        (packagesList + tailscalePackageName).distinct()
      }
    } else {
      (packagesList + builtInDisallowedPackages).distinct()
    }
