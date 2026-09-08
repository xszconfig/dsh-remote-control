package com.daniel.dshremote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabletLayoutTest {

    @Test
    fun isTabletLayout_boundaries() {
        assertFalse(isTabletLayout(TABLET_MIN_WIDTH_DP - 1)) // 839 → 手机
        assertTrue(isTabletLayout(TABLET_MIN_WIDTH_DP))      // 840 → 平板
        assertTrue(isTabletLayout(1280))                     // 10" 平板
    }

    @Test
    fun paneState_subagentOpen() {
        val p = tabletPaneState(currentSessionId = "sub-1", subagentReturnTo = "main-1")
        assertFalse(p.leftVisible)
        assertTrue(p.midVisible)
        assertTrue(p.rightVisible)
        assertEquals(1, p.backLevel)
    }

    @Test
    fun paneState_mainSessionOpen() {
        val p = tabletPaneState(currentSessionId = "main-1", subagentReturnTo = null)
        assertTrue(p.leftVisible)
        assertTrue(p.midVisible)
        assertFalse(p.rightVisible)
        assertEquals(2, p.backLevel)
    }

    @Test
    fun paneState_listView() {
        val p = tabletPaneState(currentSessionId = null, subagentReturnTo = null)
        assertTrue(p.leftVisible)
        assertFalse(p.midVisible)
        assertFalse(p.rightVisible)
        assertEquals(3, p.backLevel)
    }
}
