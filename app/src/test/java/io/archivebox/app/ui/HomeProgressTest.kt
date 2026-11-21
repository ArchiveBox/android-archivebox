package io.archivebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeProgressTest {
    @Test fun ageUsesElapsedTimeSinceLastSuccessfulRefresh() {
        assertEquals("Not refreshed yet", lastRefreshedLabel(null))
        assertEquals("Last refreshed 5s ago…", lastRefreshedLabel(5_900))
        assertEquals("Last refreshed 6s ago…", lastRefreshedLabel(6_000))
        assertEquals("Last refreshed 2m ago…", lastRefreshedLabel(121_000))
        assertEquals("Last refreshed 1h ago…", lastRefreshedLabel(3_600_000))
    }
}
