package org.stypox.dicio.unicodeCldrPlugin.data

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.stypox.dicio.unicodeCldrPlugin.util.UnicodeCldrPluginException
import java.io.File

@Throws(UnicodeCldrPluginException::class)
fun ensureGitRepoDownloaded(repo: String, commit: String, directory: File) {
    val git = if (directory.exists()) {
        try {
            Git.open(directory).also {
                if (it.repository.isBare || it.repository.workTree.canonicalFile != directory.canonicalFile) {
                    it.close()
                    throw IllegalStateException("Not a Git working tree: $directory")
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Cannot open Git working tree $directory; existing data was left untouched", e)
        }
    } else {
        Git.init().setDirectory(directory).call()
    }

    git.use { git ->
        if (!git.status().call().isClean) {
            throw IllegalStateException("Git working tree $directory has uncommitted changes; existing data was left untouched")
        }
        val origin = git.remoteList().call().find { it.name == "origin" }
        if (origin != null && (origin.urIs.size != 1 || origin.urIs.single().toString() != repo)) {
            throw IllegalStateException("Git working tree $directory has an unexpected origin; existing data was left untouched")
        }
        try {
            git.remoteAdd()
                .setName("origin")
                .setUri(URIish(repo))
                .call()
        } catch (_: GitAPIException) {
            git.remoteSetUrl()
                .setRemoteName("origin")
                .setRemoteUri(URIish(repo))
                .call()
        }

        git.fetch()
            .setRemote("origin")
            // if this doesn't compile, then Gradle is messing with the jgit version used at
            // runtime; in that case, revert commit "Improve unicode cldr repo cloning"
            .setDepth(1)
            .setRefSpecs(RefSpec(commit))
            .call()

        git.checkout()
            .setName(commit)
            .setStartPoint(commit)
            .call()
    }
}
