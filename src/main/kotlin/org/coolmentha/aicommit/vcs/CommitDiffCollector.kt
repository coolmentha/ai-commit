package org.coolmentha.aicommit.vcs

import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

object CommitDiffCollector {
    private const val MAX_DIFF_CHARS = 16_000
    private const val MAX_UNVERSIONED_FILE_BYTES = 12_000L
    private const val GIT_COMMAND_TIMEOUT_SECONDS = 5L

    fun collect(
        project: Project,
        includedChanges: Collection<Change>,
        includedUnversionedFiles: Collection<FilePath>,
        isAmendCommit: Boolean = false,
    ): String {
        val currentParts = mutableListOf<String>()
        if (includedChanges.isNotEmpty()) {
            currentParts += buildPatch(project, includedChanges)
        }
        if (includedUnversionedFiles.isNotEmpty()) {
            currentParts += renderUnversionedFiles(project, includedUnversionedFiles)
        }

        val currentDiff = currentParts.filter { it.isNotBlank() }.joinToString(separator = "\n\n").trim()
        val amendBaseDiff = if (isAmendCommit) {
            collectLastCommitDiff(resolveProjectRoot(project))
        } else {
            ""
        }

        val raw = composeDiff(amendBaseDiff, currentDiff, isAmendCommit)
        if (raw.isBlank()) {
            return ""
        }
        return if (raw.length <= MAX_DIFF_CHARS) {
            raw
        } else {
            raw.take(MAX_DIFF_CHARS) + "\n\n[diff 已截断，仅保留前 $MAX_DIFF_CHARS 个字符]"
        }
    }

    fun currentBranch(project: Project): String {
        val basePath = project.basePath ?: return "unknown"
        return try {
            val process = ProcessBuilder("git", "-C", basePath, "rev-parse", "--abbrev-ref", "HEAD")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "unknown"
            }
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                reader.readText().trim().takeIf { it.isNotBlank() && it != "HEAD" } ?: "unknown"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    internal fun collectLastCommitDiff(projectRoot: Path): String {
        return runGitCommand(projectRoot, "show", "--format=", "--no-ext-diff", "HEAD")
    }

    internal fun composeDiff(
        amendBaseDiff: String,
        currentDiff: String,
        isAmendCommit: Boolean,
    ): String {
        if (!isAmendCommit) {
            return currentDiff.trim()
        }

        val parts = mutableListOf<String>()
        if (amendBaseDiff.isNotBlank()) {
            parts += renderDiffSection("amend 基础 diff（最近一次提交 HEAD）", amendBaseDiff)
        }
        if (currentDiff.isNotBlank()) {
            parts += renderDiffSection("amend 追加 diff（当前勾选改动）", currentDiff)
        }
        return parts.joinToString(separator = "\n\n").trim()
    }

    private fun buildPatch(project: Project, includedChanges: Collection<Change>): String {
        val basePath = resolveProjectRoot(project)
        return try {
            val patches = IdeaTextPatchBuilder.buildPatch(project, includedChanges, basePath, false)
            val writer = StringWriter()
            UnifiedDiffWriter.write(project, patches, writer, "", CommitContext())
            writer.toString().trim()
        } catch (error: VcsException) {
            throw IllegalStateException("构建待提交 diff 失败：${error.message}", error)
        }
    }

    private fun renderUnversionedFiles(project: Project, files: Collection<FilePath>): String {
        val projectRoot = resolveProjectRoot(project)
        return files.joinToString(separator = "\n\n") { file ->
            val path = file.ioFile.toPath()
            val relative = projectRoot.relativize(path).toString().replace('\\', '/')
            buildString {
                append("diff --git a/$relative b/$relative\n")
                append("new file mode 100644\n")
                append("--- /dev/null\n")
                append("+++ b/$relative\n")
                append(readFilePreview(path))
            }.trim()
        }
    }

    private fun readFilePreview(path: Path): String {
        if (!Files.isRegularFile(path)) {
            return "@@ 0,0 @@\n[无法读取非普通文件]"
        }

        val size = Files.size(path)
        if (size > MAX_UNVERSIONED_FILE_BYTES) {
            return "@@ 0,0 @@\n[文件过大，已省略内容]"
        }

        val content = try {
            Files.readString(path, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return "@@ 0,0 @@\n[文件不是 UTF-8 文本或读取失败]"
        }

        return "@@ 0,0 @@\n$content"
    }

    private fun resolveProjectRoot(project: Project): Path {
        return project.basePath?.let(Path::of) ?: error("当前项目没有可用的根目录。")
    }

    private fun renderDiffSection(title: String, diff: String): String {
        return buildString {
            append("[")
            append(title)
            append("]\n")
            append(diff.trim())
        }.trim()
    }

    private fun runGitCommand(projectRoot: Path, vararg args: String): String {
        return try {
            val process = ProcessBuilder(buildList {
                add("git")
                add("-C")
                add(projectRoot.toString())
                addAll(args)
            }).redirectErrorStream(true).start()

            if (!process.waitFor(GIT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return ""
            }

            if (process.exitValue() != 0) {
                return ""
            }

            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                reader.readText().trim()
            }
        } catch (_: Exception) {
            ""
        }
    }
}
