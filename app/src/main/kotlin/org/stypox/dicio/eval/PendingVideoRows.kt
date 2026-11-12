package org.stypox.dicio.eval

/** Main-thread only. A successful selection invalidates every pending row. */
internal class PendingVideoRows<T> {
    private class Row<T>(val query: String, val options: MutableMap<String, T>)

    private val rows = mutableMapOf<Long, Row<T>>()
    private var lastId = 0L

    fun create(query: String, options: Map<String, T>): Long {
        check(lastId < Long.MAX_VALUE) { "Pending video row IDs exhausted" }
        val id = ++lastId
        rows[id] = Row(query, options.toMutableMap())
        return id
    }

    fun extend(rowId: Long, options: Map<String, T>): Boolean {
        val row = rows[rowId] ?: return false
        row.options.putAll(options)
        return true
    }

    fun consume(rowId: Long, optionId: String): Pair<String, T>? {
        val row = rows[rowId] ?: return null
        if (!row.options.containsKey(optionId)) return null
        val result = row.query to row.options.getValue(optionId)
        rows.clear()
        return result
    }

    fun clear() {
        rows.clear()
    }
}
