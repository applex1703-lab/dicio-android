package org.stypox.dicio.llm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RutubeResultsTest : StringSpec({
    "valid results are bounded, deduplicated and keep only safe image hosts" {
        val body = """{"results":[
          {"id":"0123456789abcdef0123456789abcdef","title":"Видео","author":{"name":"Автор"},"thumbnail_url":"https://pic.rtbcdn.ru/a.jpg"},
          {"id":"0123456789abcdef0123456789abcdef","title":"Duplicate"},
          {"id":"bad","title":"Invalid"},
          {"id":"1123456789abcdef0123456789abcdef","title":"Other","thumbnail_url":"https://evil.example/a.jpg"}
        ]}"""
        val results = RutubeResultsParser.parse(body)
        results.size shouldBe 2
        results[0].title shouldBe "Видео"
        results[0].thumbnail shouldBe "https://pic.rtbcdn.ru/a.jpg"
        results[1].thumbnail shouldBe null
    }
    "empty results are not fabricated" {
        RutubeResultsParser.parse("""{"results":[]}""") shouldBe emptyList()
    }
})
