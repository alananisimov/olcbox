package org.olcbox.daemon.tunnel

import org.olcbox.daemon.ipc.LogEntry

// Bounded, thread-safe ring buffer backing the /tunnel/logs?since= polling
// endpoint. The cursor is a monotonically increasing counter, not an array
// index, so "since=<cursor>" stays meaningful across trims.
internal class LogBuffer(private val capacity: Int = 5_000) {
    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>()
    private var nextCursor = 0L

    fun append(source: String, line: String) {
        synchronized(lock) {
            entries.addLast(LogEntry(cursor = nextCursor, source = source, line = line))
            nextCursor++
            while (entries.size > capacity) {
                entries.removeFirst()
            }
        }
    }

    fun since(cursor: Long): Pair<List<LogEntry>, Long> {
        synchronized(lock) {
            return entries.filter { it.cursor > cursor } to nextCursor
        }
    }
}
