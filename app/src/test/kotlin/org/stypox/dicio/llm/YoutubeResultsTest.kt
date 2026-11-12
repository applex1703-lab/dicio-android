package org.stypox.dicio.llm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class YoutubeResultsTest : StringSpec({
    "parses videos, decodes entities and validates direct thumbnails" {
        val page = YoutubeResultsParser.parsePage("""{"nextPageToken":"CAoQAA","items":[
          {"id":{"kind":"youtube#video","videoId":"abcDEF123_-"},"snippet":{"title":"Rock &amp; &#39;Roll&#39; &#x1F600;","channelTitle":"A &quot;B&quot;","thumbnails":{"high":{"url":"https://evil.example/a"},"medium":{"url":"https://i.ytimg.com/vi/abcDEF123_-/mqdefault.jpg"}}}},
          {"id":{"videoId":"abcDEF123_-"},"snippet":{"title":"Duplicate"}},
          {"id":{"videoId":"bad"},"snippet":{"title":"Invalid"}},
          {"id":{"kind":"youtube#channel","videoId":"12345678901"},"snippet":{"title":"Channel"}},
          {"id":{"videoId":"12345678901"},"snippet":{"title":"Other","thumbnails":{"default":{"url":"https://i.ytimg.com.evil.example/image"}}}}
        ]}""")
        page.nextPageToken shouldBe "CAoQAA"
        page.videos.size shouldBe 2
        page.videos[0] shouldBe YoutubeVideo("abcDEF123_-", "Rock & 'Roll' 😀", "A \"B\"", "https://i.ytimg.com/vi/abcDEF123_-/mqdefault.jpg")
        page.videos[1].thumbnail shouldBe null
    }
    "empty page and invalid metadata" {
        YoutubeResultsParser.parsePage("""{"items":[]}""") shouldBe YoutubePage(emptyList(), null)
        listOf("{}", "not json", """{"items":{}}""", """{"items":[],"nextPageToken":null}""",
            """{"items":[],"nextPageToken":""}""", """{"items":[],"nextPageToken":"abc&x=1"}""",
            """{"items":[null,{}]}""").forEach { body ->
            shouldThrow<Exception> { YoutubeResultsParser.parsePage(body) }
        }
    }
    "bounds videos and text, rejects unsafe image schemes and credentials" {
        val entries = (0..22).joinToString(",") {
            """{"id":{"videoId":"${it.toString().padStart(11, '0')}"},"snippet":{"title":"${"a".repeat(300)}","channelTitle":"${"b".repeat(150)}","thumbnails":{"high":{"url":"https://user@i.ytimg.com/a"},"default":{"url":"http://i.ytimg.com/a"}}}}"""
        }
        val videos = YoutubeResultsParser.parse("""{"items":[$entries]}""")
        videos.size shouldBe 20
        videos[0].title.length shouldBe 200
        videos[0].author.length shouldBe 100
        videos[0].thumbnail shouldBe null
    }
    "request parameters and opaque pagination token are encoded without key in URL" {
        val provider = YoutubeResults("fake-test-key")
        val first = provider.request("cats & dogs", null)
        first.url.queryParameter("part") shouldBe "snippet"
        first.url.queryParameter("type") shouldBe "video"
        first.url.queryParameter("maxResults") shouldBe "20"
        first.url.queryParameter("q") shouldBe "cats & dogs"
        first.url.queryParameter("pageToken") shouldBe null
        first.url.toString().contains("fake-test-key") shouldBe false
        first.header("X-Goog-Api-Key") shouldBe "fake-test-key"
        provider.request("cats", "CAoQAA").url.queryParameter("pageToken") shouldBe "CAoQAA"
        listOf("", "x&key=secret", "x y", "a".repeat(513)).forEach { token ->
            shouldThrow<IllegalArgumentException> { provider.request("cats", token) }
        }
    }
})
