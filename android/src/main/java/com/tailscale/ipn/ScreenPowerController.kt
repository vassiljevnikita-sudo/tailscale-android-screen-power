// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Serialize backend edits and coalesce display events without losing a manual disconnect. */
class ScreenPowerController(
    scope: CoroutineScope,
    shouldRun: () -> Boolean,
    connectionRequested: () -> Boolean,
    applyRunning: suspend (Boolean) -> Boolean,
    stopIfDisconnected: suspend () -> Boolean,
) {
  private val updates = Channel<Unit>(Channel.CONFLATED)
  private val job = scope.launch {
    for (update in updates) {
      val running = shouldRun()
      if (!applyRunning(running)) continue
      // A screen event or user disconnect can arrive while the backend call is in
      // flight. Reconcile before closing, otherwise the last edit could leave
      // WantRunning=true after a manual stop.
      if (running != shouldRun()) {
        updates.trySend(Unit)
        continue
      }
      if (!connectionRequested() && stopIfDisconnected()) break
    }
  }

  fun update() {
    updates.trySend(Unit)
  }

  fun close() {
    updates.close()
    job.cancel()
  }
}
