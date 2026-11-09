package org.stypox.dicio.skills

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.stypox.dicio.skills.search.parseSearchResults
import org.stypox.dicio.skills.translation.findTranslationLocale

class SkillNetworkRobustnessTest : StringSpec({
    "translation selects the most specific locale at a subtag boundary" {
        findTranslationLocale("zh-Hant-TW") shouldBe "zh_HANT"
        findTranslationLocale("zh_CN") shouldBe "zh"
        findTranslationLocale("english") shouldBe null
    }
    "search tolerates missing optional fields and rejects unsafe destinations" {
        val document = Jsoup.parse("""
            <div class="result__body extra"><a class="result__a extra" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fx">Example</a></div>
            <div class="result__body"><a class="result__a" href="javascript:alert(1)">Unsafe</a></div>
            <div class="result__body"><a class="result__a" href="https://example.org/">Direct</a></div>
        """.trimIndent(), "https://duckduckgo.com")
        val results = parseSearchResults(document)
        results.map { it.url } shouldBe listOf("https://example.com/x", "https://example.org/")
        results.first().thumbnailUrl shouldBe ""
        results.first().description shouldBe ""
    }
})
