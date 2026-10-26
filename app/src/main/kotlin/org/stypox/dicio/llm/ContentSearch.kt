package org.stypox.dicio.llm

internal object ContentSearch {
    const val RUTUBE_PACKAGE = "rtb.mobile.android"

    fun validateQuery(query: String): String {
        check(query.isNotBlank() && query.length <= 300 && query.none { it.isISOControl() }) {
            "Поисковая фраза пустая, слишком длинная или содержит управляющие символы."
        }
        return query.trim()
    }
}
