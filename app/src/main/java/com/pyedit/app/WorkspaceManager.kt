package com.pyedit.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest

class WorkspaceManager(private val context: Context) {

    sealed class FileNode {
        abstract val doc: DocumentFile
        abstract val name: String

        data class Folder(
            override val doc: DocumentFile,
            override val name: String,
            val children: List<FileNode>
        ) : FileNode()

        data class Leaf(
            override val doc: DocumentFile,
            override val name: String
        ) : FileNode()
    }

    fun buildTree(rootUri: Uri): List<FileNode> {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
        return buildTreeFrom(root)
    }

    private fun buildTreeFrom(dir: DocumentFile): List<FileNode> {
        val children = dir.listFiles()
        return children
            .filter { it.isDirectory || (it.name?.endsWith(".py") == true) }
            .sortedWith(
                compareByDescending<DocumentFile> { it.isDirectory }
                    .thenBy { (it.name ?: "").lowercase() }
            )
            .map { child ->
                if (child.isDirectory) {
                    FileNode.Folder(child, child.name ?: "", buildTreeFrom(child))
                } else {
                    FileNode.Leaf(child, child.name ?: "")
                }
            }
    }

    fun hasAnyPythonFile(nodes: List<FileNode>): Boolean = nodes.any { node ->
        when (node) {
            is FileNode.Leaf -> true
            is FileNode.Folder -> hasAnyPythonFile(node.children)
        }
    }

    /** Display name of the folder a tree URI points to (for the Root
     * Folder row's middle label). */
    fun folderDisplayName(rootUri: Uri): String =
        DocumentFile.fromTreeUri(context, rootUri)?.name ?: ""

    fun readFile(doc: DocumentFile): String {
        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            return input.bufferedReader().readText()
        }
        return ""
    }

    fun saveFile(doc: DocumentFile, content: String) {
        context.contentResolver.openOutputStream(doc.uri, "wt")?.use { output ->
            output.write(content.toByteArray())
        }
    }

    fun createNewFileInTree(rootUri: Uri, name: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        val fileName = if (name.endsWith(".py")) name else "$name.py"
        return root.findFile(fileName) ?: root.createFile("text/x-python", fileName)
    }

    /** Rename via SAF's DocumentFile.renameTo — returns false if the
     * provider rejects it (e.g. a name collision). */
    fun renameFile(doc: DocumentFile, newName: String): Boolean {
        val fileName = if (newName.endsWith(".py")) newName else "$newName.py"
        return try {
            doc.renameTo(fileName)
        } catch (t: Throwable) {
            false
        }
    }

    fun deleteFile(doc: DocumentFile): Boolean = try {
        doc.delete()
    } catch (t: Throwable) {
        false
    }

    private fun recoveryKeyFor(uri: Uri): String {
        val digest = MessageDigest.getInstance("MD5").digest(uri.toString().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun recoveryDir() = context.cacheDir.resolve("recovery").apply { mkdirs() }

    fun writeRecovery(doc: DocumentFile, content: String) {
        recoveryDir().resolve(recoveryKeyFor(doc.uri)).writeText(content)
    }

    fun readRecoveryIfDifferent(doc: DocumentFile): String? {
        val file = recoveryDir().resolve(recoveryKeyFor(doc.uri))
        if (!file.exists()) return null
        val recoveryContent = file.readText()
        val savedContent = readFile(doc)
        return if (recoveryContent != savedContent) recoveryContent else null
    }

    fun clearRecovery(doc: DocumentFile) {
        recoveryDir().resolve(recoveryKeyFor(doc.uri)).delete()
    }
}