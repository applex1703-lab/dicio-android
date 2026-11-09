package org.stypox.dicio.unicodeCldrPlugin.data

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.stypox.dicio.unicodeCldrPlugin.util.UnicodeCldrPluginException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

@Throws(UnicodeCldrPluginException::class)
fun ensureGitRepoDownloaded(repo: String, commit: String, directory: File) {
    if (!commit.matches(Regex("[0-9a-f]{40}"))) {
        throw UnicodeCldrPluginException("Expected a full lowercase commit ID, got $commit")
    }
    if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        validateCheckout(repo, commit, directory)
        return
    }

    directory.absoluteFile.parentFile.mkdirs()
    if (!directory.mkdir()) {
        throw UnicodeCldrPluginException("Cannot create $directory; existing data was left untouched")
    }
    try {
        Git.init().setDirectory(directory).call().use { git ->
            git.remoteAdd().setName("origin").setUri(URIish(repo)).call()
            git.fetch().setRemote("origin").setDepth(1).setRefSpecs(RefSpec(commit)).call()
            git.checkout().setName(commit).call()
        }
        validateCheckout(repo, commit, directory)
    } catch (e: Exception) {
        throw UnicodeCldrPluginException("Cannot create checkout in $directory; data was left untouched", e)
    }
}

private fun validateCheckout(repo: String, commit: String, directory: File) {
    try {
        if (!directory.isDirectory || !File(directory, ".git").exists()) {
            throw IllegalStateException("not a Git working tree")
        }
        Git.open(directory).use { git ->
            val repository = git.repository
            check(!repository.isBare && repository.workTree.canonicalFile == directory.canonicalFile) {
                "not its own Git working tree"
            }
            val origin = git.remoteList().call().find { it.name == "origin" }
            check(origin != null && origin.urIs.size == 1 && origin.urIs.single().toString() == repo) {
                "unexpected origin (expected $repo)"
            }
            val head = repository.resolve("HEAD")?.name
            check(head == commit && git.status().call().isClean) {
                "must be clean and at commit $commit (HEAD: ${head ?: "unborn"})"
            }
        }
    } catch (e: Exception) {
        throw UnicodeCldrPluginException("Cannot use checkout $directory: ${e.message}. Existing data was left untouched", e)
    }
}
