package io.archivebox.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeProgressParserTest {
    @Test fun selectsLatestActiveCrawlAndReadsCollection() {
        val parsed = parseHomeProgress(JSONObject("""
            {"crawls_active":2,
             "active_crawls":[
               {"status":"started","started":"2026-11-20T10:01:00Z","label":"https://latest.example/"},
               {"status":"started","started":"2026-11-20T10:00:00Z","label":"https://earlier.example/"},
               {"status":"paused","started":"2026-11-20T10:02:00Z","label":"https://paused.example/"}],
             "collection":{"snapshots":53306,"bytes":2400000000000}}
        """.trimIndent()))
        assertEquals(HomeProgress(2, "https://latest.example/", 53306, 2400000000000), parsed)
    }

    @Test fun absenceOfCollectionDoesNotFabricateZeros() {
        val parsed = parseHomeProgress(JSONObject("""{"crawls_active":0}"""))
        assertEquals(0, parsed.activeCrawls)
        assertNull(parsed.snapshots)
        assertNull(parsed.archiveBytes)
    }

    @Test fun errorAndInvalidCountDoNotCountAsRefreshes() {
        for (body in listOf("""{"error":"Server starting","crawls_active":0}""", """{"crawls_active":"0"}""", "{}")) {
            assertThrows(HomeProgressUnavailable::class.java) { parseHomeProgress(JSONObject(body)) }
        }
    }
}
