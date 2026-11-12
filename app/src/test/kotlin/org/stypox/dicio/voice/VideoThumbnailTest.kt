package org.stypox.dicio.voice

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.eval.VideoSource

class VideoThumbnailTest : StringSpec({
    "thumbnails are restricted to the selected source and HTTPS port 443" {
        val youtube = "https://i.ytimg.com/vi/abcdefghijk/default.jpg"
        val rutube = "https://pic.rutube.ru/image.jpg"
        allowedVideoThumbnail(youtube, VideoSource.YOUTUBE) shouldBe youtube
        allowedVideoThumbnail(rutube, VideoSource.RUTUBE) shouldBe rutube
        allowedVideoThumbnail(youtube, VideoSource.RUTUBE) shouldBe null
        allowedVideoThumbnail(rutube, VideoSource.YOUTUBE) shouldBe null
        allowedVideoThumbnail("http://i.ytimg.com/a", VideoSource.YOUTUBE) shouldBe null
        allowedVideoThumbnail("https://i.ytimg.com:444/a", VideoSource.YOUTUBE) shouldBe null
        allowedVideoThumbnail("https://user@i.ytimg.com/a", VideoSource.YOUTUBE) shouldBe null
        allowedVideoThumbnail("https://@i.ytimg.com/a", VideoSource.YOUTUBE) shouldBe null
        allowedVideoThumbnail("https://i.ytimg.com.example.org/a", VideoSource.YOUTUBE) shouldBe null
    }
})
