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

    /** Lists folders that contain at least one supported file, plus the
     * supported files themselves (the one centralized filter: Languages). */
    private fun buildTreeFrom(dir: DocumentFile): List<FileNode> {
        val children = dir.listFiles()
        return children
            .mapNotNull { child ->
                if (child.isDirectory) {
                    val subChildren = buildTreeFrom(child)
                    if (hasAnyPythonFile(subChildren)) {
                        FileNode.Folder(child, child.name ?: "", subChildren)
                    } else {
                        null
                    }
                } else if (Languages.isDiscoverable(child.name ?: "")) {
                    FileNode.Leaf(child, child.name ?: "")
                } else {
                    null
                }
            }
            .sortedWith(
                compareByDescending<FileNode> { it is FileNode.Folder }
                    .thenBy { it.name.lowercase() }
            )
    }

    /** True if the tree contains any supported file (name kept for callers). */
    fun hasAnyPythonFile(nodes: List<FileNode>): Boolean = nodes.any { node ->
        when (node) {
            is FileNode.Leaf -> true
            is FileNode.Folder -> hasAnyPythonFile(node.children)
        }
    }

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

    private fun withSupportedExtension(name: String) =
        if (Languages.isDiscoverable(name)) name else "$name.py"

    fun createNewFileInTree(rootUri: Uri, name: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        val fileName = withSupportedExtension(name)
        // A generic MIME type stops providers from appending their own extension.
        val mime = if (fileName.endsWith(".py")) "text/x-python" else "application/octet-stream"
        return root.findFile(fileName) ?: root.createFile(mime, fileName)
    }

    fun renameFile(doc: DocumentFile, newName: String): Boolean {
        val fileName = withSupportedExtension(newName)
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