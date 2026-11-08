package org.stypox.dicio.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class BinaryFileDownloaderTest : StringSpec({
    fun response(code: Int, content: String): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/file").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(content.toResponseBody())
        .build()

    "a URL marker without the downloaded file requires a download" {
        val base = File("build/tmp/downloader-tests").apply { mkdirs() }
        val dir = Files.createTempDirectory(base.toPath(), "marker-").toFile()
        val file = File(dir, "file")
        val download = FileToDownload("https://example.com/file", file)
        try {
            download.lastDownloadedUrlFile.writeText(download.url)
            download.needsToBeDownloaded() shouldBe true
            file.writeText("data")
            download.needsToBeDownloaded() shouldBe false
        } finally {
            file.delete()
            download.lastDownloadedUrlFile.delete()
            dir.delete()
        }
    }

    "HTTP errors are not written as downloaded data" {
        val output = ByteArrayOutputStream()
        var failed = false
        try {
            runBlocking { response(404, "not found").use { downloadBinaryFile(it, output) { _, _ -> } } }
        } catch (_: IOException) {
            failed = true
        }
        failed shouldBe true
        output.size() shouldBe 0
    }

    "failed downloads preserve the previous file and remove the partial file" {
        val base = File("build/tmp/downloader-tests").apply { mkdirs() }
        val dir = Files.createTempDirectory(base.toPath(), "failure-").toFile()
        val file = File(dir, "x").apply { writeText("old") }
        try {
            var failed = false
            try {
                runBlocking { downloadBinaryFileWithPartial(response(500, "error"), file, dir) { _, _ -> } }
            } catch (_: IOException) {
                failed = true
            }
            failed shouldBe true
            file.readText() shouldBe "old"
            dir.listFiles()!!.map { it.name } shouldBe listOf("x")
        } finally {
            file.delete()
            dir.delete()
        }
    }
})
