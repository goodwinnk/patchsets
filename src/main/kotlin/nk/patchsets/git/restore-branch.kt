@file:Suppress("PackageDirectoryMismatch")
@file:JvmName("BunchRestore")

package nk.patchsets.git.restore

import nk.patchsets.git.ChangeType
import nk.patchsets.git.FileChange
import nk.patchsets.git.commitChanges
import java.io.File

data class Settings(val repoPath: String, val rule: String, val commitTitle: String = "==== switch ====")

val patchesSettings = Settings(
        repoPath = "patches",
        rule = "173->172->171->as30"
)

val kotlinSettings = Settings(
        repoPath = "C:/Projects/kotlin",
        rule = "173->172->171->as30"
)

fun main(args: Array<String>) {
    restore(args)
}

fun restore(args: Array<String>) {
    if (args.size != 3 && args.size != 2) {
        System.err.println("""
            Usage: <git-path> <branches-rule> <commit-title>?

            <git-path> - Directory with repository (parent directory for .git folder).
            <branches-rule> - Set of file suffixes separated with `_` showing what files should be affected and priority
                              of application. If only target branch is given file <git-path>/.bunch will be checked for
                              pattern.
            <commit-title> - Title for switch commit. "==== switch {target} ====" is used by default. {target} pattern
                             in message will be replaced with target branch suffix.
            Example:
            <program> C:/Projects/kotlin 173_as31_as32
            """.trimIndent())

        return
    }

    val settings = Settings(
            repoPath = args[0],
            rule = args[1],
            commitTitle = args.getOrElse(2, { "==== switch {target} ====" })
    )

    val suffixes = getRuleSuffixes(settings)
    if (suffixes.isEmpty()) {
        return
    }

    val originBranchExtension = suffixes.first()
    val donorExtensionsPrioritized = suffixes.subList(1, suffixes.size).reversed().toSet()

    val root = File(settings.repoPath)

    if (!root.exists() || !root.isDirectory) {
        System.err.println("Repository directory with branch is expected")
    }

    val changedFiles = HashSet<FileChange>()

    val filesWithDonorExtensions = root
            .walkTopDown()
            .onEnter { dir -> !(dir.name == "resources" && dir.parentFile.name == "build") }
            .filter { child -> child.extension in donorExtensionsPrioritized }
            .toList()

    val affectedOriginFiles: Set<File> =
            filesWithDonorExtensions.mapTo(HashSet(), { child -> File(child.parentFile, child.nameWithoutExtension) })

    for (originFile in affectedOriginFiles) {
        val originFileModificationType: ChangeType

        if (originFile.exists()) {
            if (originFile.isDirectory) {
                System.err.println("Patch specific directories are not supported: ${originFile}")
                return
            }

            val branchCopyFile = originFile.toPatchFile(originBranchExtension)

            if (branchCopyFile.exists()) {
                System.err.println("Can't store copy of the origin file, because branch file is already exist: ${branchCopyFile}")
                return
            }
            originFile.copyTo(branchCopyFile)
            changedFiles.add(FileChange(ChangeType.ADD, branchCopyFile))

            originFile.delete()
            originFileModificationType = ChangeType.MODIFY
        } else {
            originFileModificationType = ChangeType.ADD
        }

        val targetFile = donorExtensionsPrioritized
                .asSequence()
                .map { extension -> originFile.toPatchFile(extension) }
                .first { it.exists() }

        if (targetFile.isDirectory) {
            System.err.println("Patch specific directories are not supported: $targetFile")
            return
        }

        val isTargetRemoved = targetFile.readText().trim().isEmpty()
        if (!isTargetRemoved) {
            targetFile.copyTo(originFile)
            changedFiles.add(FileChange(originFileModificationType, originFile))
        } else {
            when (originFileModificationType) {
                ChangeType.ADD -> {
                    // Original file was copied, but there's nothing to add instead. Do nothing.
                }
                ChangeType.MODIFY -> {
                    changedFiles.add(FileChange(ChangeType.REMOVE, originFile))
                }
                ChangeType.REMOVE -> {
                    throw IllegalStateException("REMOVE state isn't expected for original file modification")
                }
            }
        }
    }

    commitChanges(settings.repoPath, changedFiles, settings.commitTitle.replace("{target}", suffixes.last()))
}

private fun File.toPatchFile(extension: String) = File(parentFile, "$name.$extension")

fun getRuleSuffixes(settings: Settings): List<String> {
    val suffixes = settings.rule.split("_")
    return when {
        suffixes.isEmpty() -> {
            System.err.println("There should be at target branch in pattern: ${settings.rule}")
            emptyList()
        }

        suffixes.size == 1 -> {
            val ruleFromFile = readRuleFromFile(suffixes.first(), settings.repoPath) ?: return emptyList()
            val fileRuleSuffixes = ruleFromFile.split("_")

            if (fileRuleSuffixes.size < 2) {
                System.err.println("Only target branch is given in pattern. Do nothing.")
                emptyList()
            } else {
                fileRuleSuffixes
            }
        }

        else -> suffixes
    }
}

fun readRuleFromFile(endSuffix: String, path: String): String? {
    val file = File(path, ".bunch")
    if (!file.exists()) {
        System.err.println("Can't build rule for restore branch from '$endSuffix'. File '${file.canonicalPath}' doesn't exist ")
        return endSuffix
    }

    val branchRules = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }

    val currentBranchSuffix = branchRules.firstOrNull()
    if (currentBranchSuffix == null) {
        System.err.println("First line in '${file.canonicalPath}' should contain current branch name")
        return null
    }

    if (currentBranchSuffix == endSuffix) {
        return endSuffix
    }

    val requestedRule = branchRules.find { it == endSuffix || it.startsWith(endSuffix + "_") }
    if (requestedRule == null) {
        System.err.println("Can't find rule for '$endSuffix' in file '${file.canonicalPath}'")
        return null
    }

    val ruleTargetToCurrent = (requestedRule + "_$currentBranchSuffix").split("_")

    return ruleTargetToCurrent.reversed().joinToString(separator = "_")
}