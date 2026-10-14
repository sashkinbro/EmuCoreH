package com.sbro.emucoreh.data.boxart

import org.junit.Assert.*
import org.junit.Test

class BoxartRetryPolicyTest {
    @Test fun `exhausted short retries preserve another background attempt`() {
        assertEquals(60_000L, BoxartRetryPolicy.nextSyncDelay(2_000L, 1_000L))
    }

    @Test fun `API allowance backoff schedules recovery at the deadline`() {
        assertEquals(86_400_000L, BoxartRetryPolicy.nextSyncDelay(86_401_000L, 1_000L))
    }

    @Test fun `successful lookup does not schedule a redundant sync`() {
        assertNull(BoxartRetryPolicy.nextSyncDelay(0L, 1_000L))
    }
}
