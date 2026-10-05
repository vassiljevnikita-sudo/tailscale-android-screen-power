// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.content.Context
import android.content.SharedPreferences

/** Event-driven screen policy; no polling, wake locks or periodic work. */
object ScreenPowerSettings {
  const val ENABLED = "screenPowerEnabled"
  private const val REQUESTED = "connectionRequested"

  fun prefs(context: Context): SharedPreferences =
      context.getSharedPreferences("screen-power", Context.MODE_PRIVATE)

  fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, true)

  fun setEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(ENABLED, enabled).apply()
  }

  fun connectionRequested(context: Context): Boolean = prefs(context).getBoolean(REQUESTED, false)

  fun setConnectionRequested(context: Context, requested: Boolean) {
    // This intent must survive process death even when the backend's WantRunning is false.
    prefs(context).edit().putBoolean(REQUESTED, requested).commit()
  }

  fun shouldRun(
      requested: Boolean,
      interactive: Boolean,
      enabled: Boolean,
      forceEnabled: Boolean,
      lockdown: Boolean,
  ): Boolean = requested && (interactive || !enabled || forceEnabled || lockdown)
}
