package com.livingpresence.inner.circle.squared

import android.content.ComponentCallbacks2
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests the trim-tier logic (plan.md Phase 4): MODERATE → SHRINK, LOW → RELEASE,
 * other → no-op. Pure logic, no Android runtime needed.
 */
class MemoryGovernorTest {

    @Test
    fun moderateLevels_shrinkTheFrameEngine() {
        // RUNNING_MODERATE (5), RUNNING_LOW (10) → SHRINK.
        assertEquals(MemoryGovernor.TrimTier.SHRINK, MemoryGovernor.tierFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(MemoryGovernor.TrimTier.SHRINK, MemoryGovernor.tierFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
    }

    @Test
    fun lowAndCriticalLevels_releaseTheFrameEngine() {
        // RUNNING_CRITICAL (15), MODERATE (60), COMPLETE (80), UI_HIDDEN (20) → RELEASE.
        assertEquals(MemoryGovernor.TrimTier.RELEASE, MemoryGovernor.tierFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(MemoryGovernor.TrimTier.RELEASE, MemoryGovernor.tierFor(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertEquals(MemoryGovernor.TrimTier.RELEASE, MemoryGovernor.tierFor(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
        assertEquals(MemoryGovernor.TrimTier.RELEASE, MemoryGovernor.tierFor(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    }

    @Test
    fun backgroundLevel_returnsNoTier() {
        // BACKGROUND (40) — process backgrounded, not a memory-pressure signal we
        // act on here (the service owns the player) → no-op.
        assertNull(MemoryGovernor.tierFor(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
    }

    @Test
    fun veryLowLevels_returnNoTier() {
        assertNull(MemoryGovernor.tierFor(0))
        assertNull(MemoryGovernor.tierFor(1))
    }

    @Test
    fun onTrim_appliesTheTierToTheTarget() {
        var applied: MemoryGovernor.TrimTier? = null
        val target = MemoryGovernor.TrimTarget { applied = it }

        MemoryGovernor.onTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, target)
        assertEquals(MemoryGovernor.TrimTier.SHRINK, applied)

        MemoryGovernor.onTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, target)
        assertEquals(MemoryGovernor.TrimTier.RELEASE, applied)
    }

    @Test
    fun onTrim_nullTargetIsSafe() {
        // No crash when no target is registered (e.g. before the composition runs).
        MemoryGovernor.onTrim(ComponentCallbacks2.TRIM_MEMORY_MODERATE, null)
    }
}
