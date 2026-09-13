package com.pyedit.app

import android.content.Context
import java.io.File

class WorkspaceManager(context: Context) {

    val rootDir: File = (context.getExternalFilesDir(null) ?: context.filesDir)
        .resolve("workspace")
        .apply { if (!exists()) mkdirs() }

    sealed class FileNode {
        abstract val file: File
        abstract val name: String

        data class Folder(
            override val file: File,
            override val name: String,
            val children: List<FileNode>
        ) : FileNode()

        data class Leaf(
            override val file: File,
            override val name: String
        ) : FileNode()
    }

    fun buildTree(dir: File = rootDir): List<FileNode> {
        val entries = dir.listFiles() ?: return emptyList()
        return entries
            .filter { it.isDirectory || it.extension == "py" }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .map { entry ->
                if (entry.isDirectory) {
                    FileNode.Folder(entry, entry.name, buildTree(entry))
                } else {
                    FileNode.Leaf(entry, entry.name)
                }
            }
    }

    fun readFile(file: File): String = file.readText()

    fun saveFile(file: File, content: String) {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.writeText(content)
        tempFile.renameTo(file)
    }

    fun createNewFile(name: String, parent: File = rootDir): File {
        val fileName = if (name.endsWith(".py")) name else "$name.py"
        val target = File(parent, fileName)
        if (!target.exists()) {
            target.createNewFile()
        }
        return target
    }

    fun saveAs(content: String, newName: String, parent: File = rootDir): File {
        val target = createNewFile(newName, parent)
        saveFile(target, content)
        return target
    }

    // --- Crash-safe recovery (spec §62) -----------------------------------
    // A separate hidden shadow file per tracked file, written on every
    // debounced edit REGARDLESS of whether autosave itself is on, so
    // unsaved work survives a crash even with autosave disabled. Its
    // extension ("recovery") is deliberately not "py", so it never shows
    // up in the Root Folder tree (buildTree only lists .py files).

    fun recoveryFileFor(file: File): File = File(file.parentFile, ".${file.name}.recovery")

    fun writeRecovery(file: File, content: String) {
        val recoveryFile = recoveryFileFor(file)
        val tempFile = File(recoveryFile.parentFile, "${recoveryFile.name}.tmp")
        tempFile.writeText(content)
        tempFile.renameTo(recoveryFile)
    }

    fun readRecoveryIfDifferent(file: File): String? {
        val recoveryFile = recoveryFileFor(file)
        if (!recoveryFile.exists()) return null
        val recoveryContent = recoveryFile.readText()
        val savedContent = if (file.exists()) file.readText() else ""
        return if (recoveryContent != savedContent) recoveryContent else null
    }

    fun clearRecovery(file: File) {
        recoveryFileFor(file).delete()
    }
}