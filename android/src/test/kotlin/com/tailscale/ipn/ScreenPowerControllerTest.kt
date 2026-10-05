// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenPowerControllerTest {
  @Test
  fun disconnectDuringConnectFinishesWithBackendStopped() = runTest {
    var requested = true
    val firstEdit = CompletableDeferred<Unit>()
    val edits = mutableListOf<Boolean>()
    var stops = 0
    val controller =
        ScreenPowerController(
            scope = backgroundScope,
            shouldRun = { requested },
            connectionRequested = { requested },
            applyRunning = {
              edits.add(it)
              if (edits.size == 1) firstEdit.await()
              true
            },
            stopIfDisconnected = {
              stops++
              true
            },
        )
    controller.update()
    runCurrent()
    requested = false
    controller.update()
    firstEdit.complete(Unit)
    runCurrent()
    assertEquals(listOf(true, false), edits)
    assertEquals(1, stops)
    controller.close()
  }

  @Test
  fun rapidDisplayEventsConvergeToLatestStateWithoutStoppingService() = runTest {
    var interactive = true
    val firstEdit = CompletableDeferred<Unit>()
    val edits = mutableListOf<Boolean>()
    var stops = 0
    val controller =
        ScreenPowerController(
            scope = backgroundScope,
            shouldRun = { interactive },
            connectionRequested = { true },
            applyRunning = {
              edits.add(it)
              if (edits.size == 1) firstEdit.await()
              true
            },
            stopIfDisconnected = {
              stops++
              true
            },
        )
    controller.update()
    runCurrent()
    repeat(100) {
      interactive = !interactive
      controller.update()
    }
    interactive = false
    controller.update()
    firstEdit.complete(Unit)
    runCurrent()
    assertEquals(listOf(true, false), edits)
    assertEquals(0, stops)
    interactive = true
    controller.update()
    runCurrent()
    assertEquals(true, edits.last())
    controller.close()
  }

  @Test
  fun failedBackendEditDoesNotCloseServiceAndNextEventRetries() = runTest {
    var success = false
    var attempts = 0
    var stops = 0
    val controller =
        ScreenPowerController(
            scope = backgroundScope,
            shouldRun = { false },
            connectionRequested = { false },
            applyRunning = {
              attempts++
              success
            },
            stopIfDisconnected = {
              stops++
              true
            },
        )
    controller.update()
    runCurrent()
    assertEquals(0, stops)
    success = true
    controller.update()
    runCurrent()
    assertEquals(2, attempts)
    assertEquals(1, stops)
    controller.close()
  }
}
