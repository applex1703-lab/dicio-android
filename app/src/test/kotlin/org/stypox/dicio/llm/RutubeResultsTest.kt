package org.stypox.dicio.llm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.runBlocking

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
    "page uses only has_next and ignores next URL" {
        RutubeResultsParser.parsePage("""{"results":[],"has_next":true,"next":"https://evil.example/"}""") shouldBe
            RutubePage(emptyList(), true)
        RutubeResultsParser.parsePage("""{"results":[],"has_next":false,"next":"https://evil.example/"}""").hasNext shouldBe false
        RutubeResultsParser.parsePage("""{"results":[],"next":"https://evil.example/"}""").hasNext shouldBe false
    }
    "page skips damaged entries and deduplicates case insensitive ids" {
        val page = RutubeResultsParser.parsePage("""{"has_next":true,"results":[
          null, {}, {"id":"bad","title":"Bad"},
          {"id":"ABCDEF0123456789ABCDEF0123456789","title":"First"},
          {"id":"abcdef0123456789abcdef0123456789","title":"Duplicate"}
        ]}""")
        page.hasNext shouldBe true
        page.videos.size shouldBe 1
        page.videos[0].title shouldBe "First"
    }
    "page bounds results to ten" {
        val entries = (0..11).joinToString(",") { """{"id":"${it.toString(16).padStart(32, '0')}","title":"Video"}""" }
        RutubeResultsParser.parsePage("""{"results":[$entries],"has_next":true}""").videos.size shouldBe 10
    }
    "malformed page metadata or results are rejected" {
        listOf("""{"results":[],"has_next":"true"}""", """{"results":[],"has_next":null}""",
            """{"results":[],"has_next":1}""", """{"results":{}}""", """{}""", "not json")
            .forEach { body -> shouldThrow<Exception> { RutubeResultsParser.parsePage(body) } }
    }
    "nonempty page with no valid entries is an error, not an empty result" {
        shouldThrow<Exception> { RutubeResultsParser.parsePage("""{"results":[{"id":"bad","title":"bad"}]}""") }
    }
    "page numbers must be positive" {
        shouldThrow<IllegalArgumentException> { runBlocking { RutubeResults().searchPage("test", 0) } }
        shouldThrow<IllegalArgumentException> { runBlocking { RutubeResults().searchPage("test", -1) } }
    }
})
