import me.champeau.gradle.igp.gitRepositories
import org.eclipse.jgit.api.Git
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Properties

rootProject.name = "Dicio"
include(":app")
include(":skill")
// we use includeBuild here since the plugins are compile-time dependencies
includeBuild("sentences-compiler-plugin")
includeBuild("unicode-cldr-plugin")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // need to manually read version catalog because it is not available in settings.gradle.kts
    // this code is duplicate with the below but there is no way to avoid it...
    fun findInVersionCatalog(versionIdentifier: String): String {
        val regex = "^.*$versionIdentifier *= *\"([^\"]+)\".*$".toRegex()
        return File("gradle/libs.versions.toml")
            .readLines()
            .firstNotNullOf { regex.find(it)?.groupValues?.get(1) }
    }

    id("me.champeau.includegit") version findInVersionCatalog("includegitPlugin")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}


// All of the code below handles depending on libraries from git repos, in particular dicio-numbers
// and dicio-sentences-compiler. The git commits to checkout can be updated in the version catalog.
// If you want to use a local copy of the projects, you can add the following in `local.properties`:
// useLocalDicioLibraries=dicio-numbers:../dicio-numbers,dicio-sentences-compiler:../dicio-sentences-compiler

data class IncludeGitRepo(
    val name: String,
    val uri: String,
    val projectPath: String,
    val commit: String,
)

// need to manually read version catalog because it is not available in settings.gradle.kts
// this code is duplicate with the above but there is no way to avoid it...
fun findInVersionCatalog(versionIdentifier: String): String {
    val regex = "^.*$versionIdentifier *= *\"([^\"]+)\".*$".toRegex()
    return File("gradle/libs.versions.toml")
        .readLines()
        .firstNotNullOf { regex.find(it)?.groupValues?.get(1) }
}

// the list of repositories we want to include via git
val includeGitRepos: List<IncludeGitRepo> = listOf(
    IncludeGitRepo(
        name = "dicio-numbers",
        uri = "https://github.com/Stypox/dicio-numbers",
        projectPath = ":numbers",
        commit = findInVersionCatalog("dicioNumbers"),
    ),
    IncludeGitRepo(
        name = "dicio-sentences-compiler",
        uri = "https://github.com/Stypox/dicio-sentences-compiler",
        projectPath = ":sentences_compiler",
        commit = findInVersionCatalog("dicioSentencesCompiler"),
    ),
)

// read from local.properties whether the user wants to use a local clone of a library
private fun parseKeyValuePairs(input: String): Map<String, String> {
    if (input.isBlank()) {
        return mapOf()
    }
    val map = mutableMapOf<String, String>()
    val validKeys = includeGitRepos.map { it.name }.toMutableSet()
    for (pair in input.split(',')) {
        val parts = pair.split(":", limit = 2)
        if (parts.size != 2) {
            throw IllegalArgumentException("Invalid library specification in useLocalDicioLibraries: $pair")
        } else if (parts[0] !in validKeys) {
            throw IllegalArgumentException("Invalid or duplicate library name in useLocalDicioLibraries: ${parts[0]}")
        }
        validKeys.remove(parts[0])
        map[parts[0]] = parts[1]
    }
    return map
}
val localProperties: Properties = Properties().apply {
    try {
        load(FileInputStream(File(rootDir, "local.properties")))
    } catch (e: Throwable) {
        println("Warning: can't read local.properties: $e")
    }
}
val libsToUseLocally = parseKeyValuePairs(localProperties.getOrDefault("useLocalDicioLibraries", "").toString())
val existingCheckouts = mutableSetOf<String>()

// finally actually include the libraries
for (repo in includeGitRepos) {
    if (repo.name in libsToUseLocally) {
        // user wants to use a local clone of this library
        includeBuild(libsToUseLocally[repo.name]!!) {
            dependencySubstitution {
                substitute(module("git.included.build:${repo.name}"))
                    .using(project(repo.projectPath))
            }
        }
    } else {
        // Never let the plugin fetch or checkout an existing working tree. Require an
        // already clean checkout at the pinned commit and include it directly instead.
        val file = File("$rootDir/checkouts/${repo.name}")
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!file.isDirectory || !File(file, ".git").exists()) {
                throw GradleException("Git checkout ${file.absolutePath} is not a Git working tree. " +
                    "Preserve or move it manually before retrying; expected remote: ${repo.uri}")
            }
            val git = try {
                Git.open(file)
            } catch (e: Exception) {
                throw GradleException("Cannot open Git checkout ${file.absolutePath}. " +
                    "Preserve or move it manually before retrying; expected remote: ${repo.uri}", e)
            }
            git.use {
                if (it.repository.isBare || it.repository.workTree.canonicalFile != file.canonicalFile) {
                    throw GradleException("Git checkout ${file.absolutePath} is not its own Git working tree. " +
                        "Preserve or move it manually before retrying; expected remote: ${repo.uri}")
                }
                val origin = it.remoteList().call().find { remote -> remote.name == "origin" }
                if (origin == null || origin.urIs.size != 1 || origin.urIs.single().toString() != repo.uri) {
                    throw GradleException("Git checkout ${file.absolutePath} has an unexpected origin; expected ${repo.uri}. " +
                        "Existing data was left untouched; preserve or move it manually before retrying.")
                }
                val head = it.repository.resolve("HEAD")?.name
                if (head != repo.commit || !it.status().call().isClean) {
                    throw GradleException("Git checkout ${file.absolutePath} must be clean and at pinned commit ${repo.commit} " +
                        "(current HEAD: ${head ?: "unborn"}). Existing data was left untouched; " +
                        "preserve or move it manually, or update it yourself before retrying.")
                }
            }
            existingCheckouts.add(repo.name)
            includeBuild(file) {
                dependencySubstitution {
                    substitute(module("git.included.build:${repo.name}"))
                        .using(project(repo.projectPath))
                }
            }
        }
    }
}

// tell gitRepositories to clone/update any repository that has not been already included locally
if (libsToUseLocally.size + existingCheckouts.size < includeGitRepos.size) {
    gitRepositories {
        for (repo in includeGitRepos) {
            if (repo.name !in libsToUseLocally && repo.name !in existingCheckouts) {
                include(repo.name) {
                    uri.set(repo.uri)
                    commit.set(repo.commit)
                    autoInclude.set(false)
                    includeBuild("") {
                        dependencySubstitution {
                            substitute(module("git.included.build:${repo.name}"))
                                .using(project(repo.projectPath))
                        }
                    }
                }
            }
        }
    }
}
