package com.smartisan.weather.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRefreshPolicyTest {
    @Test
    fun `cold launch and elapsed interval permit foreground relocation`() {
        assertTrue(shouldRefreshLocation(null, 0L))
        assertTrue(shouldRefreshLocation(0L, 0L))
        assertTrue(shouldRefreshLocation(10L, 10L + AUTOMATIC_LOCATION_INTERVAL_MILLIS))
    }

    @Test
    fun `activity transitions do not repeatedly request location`() {
        assertFalse(shouldRefreshLocation(10L, 10L))
        assertFalse(shouldRefreshLocation(10L, 10L + AUTOMATIC_LOCATION_INTERVAL_MILLIS - 1L))
    }

    @Test
    fun `throttle window is ten minutes and survives process restart`() {
        // 时间戳来自 DataStore，跨重启仍然生效：分钟级常量必须和天气缓存阈值对齐。
        assertEquals(10L * 60L * 1_000L, AUTOMATIC_LOCATION_INTERVAL_MILLIS)
        assertFalse(shouldRefreshLocation(1_000L, 1_000L + 9L * 60L * 1_000L))
        assertTrue(shouldRefreshLocation(1_000L, 1_000L + 10L * 60L * 1_000L))
    }

    @Test
    fun `future timestamp is treated as untrusted and permits relocation`() {
        assertTrue(shouldRefreshLocation(100L, 10L))
    }
}
