package org.stypox.dicio.llm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException

class TvActionsCatalogTest : StringSpec({
    "bad entries do not hide other packages or bypass filters" {
        val entries = listOf("bad", "self", "app.factory", "ok", "ok", "other")
        val apps = TvActions.buildCatalog(entries, "self") {
            if (it == "bad") throw IllegalStateException("broken package")
            it to "x".repeat(130)
        }
        apps.map { it.packageName } shouldBe listOf("ok", "other")
        apps.map { it.id } shouldBe listOf("app_0", "app_1")
        apps.first().label.length shouldBe 120
    }
    "catalog fails closed above its limit" {
        shouldThrow<IllegalStateException> {
            TvActions.buildCatalog((0..300).toList(), "self") { "app.$it" to "$it" }
        }
    }
    "catalog does not swallow cancellation" {
        shouldThrow<CancellationException> {
            TvActions.buildCatalog(listOf("one"), "self") { throw CancellationException() }
        }
    }
})
