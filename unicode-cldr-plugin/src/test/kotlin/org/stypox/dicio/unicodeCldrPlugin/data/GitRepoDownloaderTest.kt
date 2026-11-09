package org.stypox.dicio.unicodeCldrPlugin.data

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import org.junit.Test
import org.stypox.dicio.unicodeCldrPlugin.util.UnicodeCldrPluginException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitRepoDownloaderTest {
    @Test
    fun existingCheckoutsAreOnlyReadAndValidated() {
        val directory = Files.createTempDirectory("cldr-checkout-test").toFile()
        Git.init().setDirectory(directory).call().use { git ->
            val file = directory.resolve("data.txt")
            file.writeText("original")
            git.add().addFilepattern("data.txt").call()
            val commit = git.commit().setMessage("initial").setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com").call().name
            git.remoteAdd().setName("origin").setUri(URIish("https://example.com/repo")).call()
            ensureGitRepoDownloaded("https://example.com/repo", commit, directory)
            assertFailsWith<UnicodeCldrPluginException> {
                ensureGitRepoDownloaded("https://example.com/other", commit, directory)
            }
            assertFailsWith<UnicodeCldrPluginException> {
                ensureGitRepoDownloaded("https://example.com/repo", "0".repeat(40), directory)
            }
            file.writeText("modified")
            assertFailsWith<UnicodeCldrPluginException> {
                ensureGitRepoDownloaded("https://example.com/repo", commit, directory)
            }
            assertEquals("modified", file.readText())
            assertEquals("https://example.com/repo", git.remoteList().call().single().urIs.single().toString())
        }
    }

    @Test
    fun nonRepositoryDirectoryIsPreserved() {
        val directory = Files.createTempDirectory("cldr-nonrepo-test").toFile()
        val file = directory.resolve("important")
        file.writeText("keep")
        assertFailsWith<UnicodeCldrPluginException> {
            ensureGitRepoDownloaded("https://example.com/repo", "0".repeat(40), directory)
        }
        assertTrue(file.exists())
        assertEquals("keep", file.readText())
    }
}
