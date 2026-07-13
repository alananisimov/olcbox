package org.olcbox.daemon.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogBufferTest {

    @Test
    fun sinceReturnsOnlyEntriesAfterCursor() {
        val buffer = LogBuffer()
        buffer.append("rtc", "line 1")
        buffer.append("rtc", "line 2")
        buffer.append("tun", "line 3")

        val (entries, nextCursor) = buffer.since(0)

        assertEquals(2, entries.size)
        assertEquals("line 2", entries[0].line)
        assertEquals("line 3", entries[1].line)
        assertEquals(3, nextCursor)
    }

    @Test
    fun sinceWithLatestCursorReturnsEmpty() {
        val buffer = LogBuffer()
        buffer.append("rtc", "line 1")
        val (_, cursor) = buffer.since(-1)

        val (entries, _) = buffer.since(cursor)

        assertTrue(entries.isEmpty())
    }

    @Test
    fun trimsToCapacity() {
        val buffer = LogBuffer(capacity = 3)
        repeat(5) { buffer.append("rtc", "line $it") }

        val (entries, _) = buffer.since(-1)

        assertEquals(3, entries.size)
        assertEquals("line 2", entries.first().line)
        assertEquals("line 4", entries.last().line)
    }

    @Test
    fun cursorsAreMonotonicAcrossSources() {
        val buffer = LogBuffer()
        buffer.append("rtc", "a")
        buffer.append("tun", "b")

        val (entries, _) = buffer.since(-1)

        assertTrue(entries[0].cursor < entries[1].cursor)
    }
}
