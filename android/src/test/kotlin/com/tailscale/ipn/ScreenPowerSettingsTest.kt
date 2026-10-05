// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPowerSettingsTest {
  @Test
  fun manualDisconnectSurvivesScreenOnAndPolicyOverrides() {
    for (interactive in listOf(false, true)) {
      for (enabled in listOf(false, true)) {
        for (force in listOf(false, true)) {
          for (lockdown in listOf(false, true)) {
            assertFalse(ScreenPowerSettings.shouldRun(false, interactive, enabled, force, lockdown))
          }
        }
      }
    }
  }

  @Test
  fun screenCyclePausesAndRestoresRequestedConnection() {
    val displayStates = listOf(true, false, true, false, false, true)
    assertEquals(
        listOf(true, false, true, false, false, true),
        displayStates.map { ScreenPowerSettings.shouldRun(true, it, true, false, false) },
    )
  }

  @Test
  fun disablingModeRestoresBackgroundConnectivity() {
    assertFalse(ScreenPowerSettings.shouldRun(true, false, true, false, false))
    assertTrue(ScreenPowerSettings.shouldRun(true, false, false, false, false))
  }

  @Test
  fun lockdownAndManagedForceOnRetainConnectivity() {
    assertTrue(ScreenPowerSettings.shouldRun(true, false, true, true, false))
    assertTrue(ScreenPowerSettings.shouldRun(true, false, true, false, true))
  }
}
