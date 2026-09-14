package com.pyedit.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest

/**
 * Rebuilt on Storage Access Framework (DocumentFile / content:// URIs)
 * instead of plain java.io.File, so the user can pick ANY folder or file
 * on the device via the system picker — not just browse a fixed
 * app-private directory. The root "workspace" is now whatever folder the
 * user picked with "Open Folder", persisted as a tree URI.
 */
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

    /** Spec's V1 restriction: only .py files (and folders) are shown. */
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

    /** True if the given tree contains at least one .py file anywhere. */
    fun hasAnyPythonFile(nodes: List<FileNode>): Boolean = nodes.any { node ->
        when (node) {
            is FileNode.Leaf -> true
            is FileNode.Folder -> hasAnyPythonFile(node.children)
        }
    }

    fun readFile(doc: DocumentFile): String {
        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            return input.bufferedReader().readText()
        }
        return ""
    }

    /**
     * "wt" (write-truncate) mode is used rather than a temp-file+rename
     * atomic pattern (spec's original atomic-write intent), because
     * arbitrary SAF providers don't uniformly support that pattern the
     * way a plain filesystem does. Most providers still handle a single
     * openOutputStream write safely; full cross-provider atomicity isn't
     * guaranteed here the way it was for the old app-private path.
     */
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

    // --- Crash-safe recovery -------------------------------------------
    // Kept in app-private cache storage, keyed by a hash of the file's
    // URI, rather than attempting hidden sibling files inside whatever
    // arbitrary folder the user picked (SAF providers vary in whether
    // they support that reliably).

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