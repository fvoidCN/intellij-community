package com.intellij.debugmap.listener

import com.intellij.debugmap.DebugMapService
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException
import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef

class DebugMapFileReloadListener(private val project: Project) : FileDocumentManagerListener {

  private val service get() = DebugMapService.getInstance(project)

  private data class PendingReload(
    val oldContent: String,
    val breakpoints: List<Pair<BreakpointDef, Int>>, // def (carries groupId), currentLine
    val bookmarks: List<Pair<BookmarkDef, Int>>,      // def (carries groupId), currentLine
  )

  private val pendingReloads = mutableMapOf<String, PendingReload>()

  override fun beforeFileContentReload(file: VirtualFile, document: Document) {
    val fileUrl = file.url
    val activeGroupId = service.getActiveGroupId()
    val breakpoints = service.getBreakpointsByFile(fileUrl)
      .filter { it.groupId != activeGroupId }
      .map { def -> def to service.getCurrentLine(def.groupId, def) }
    val bookmarks = service.getBookmarksByFile(fileUrl)
      .filter { it.groupId != activeGroupId }
      .map { def -> def to def.line }
    if (breakpoints.isEmpty() && bookmarks.isEmpty()) return
    pendingReloads[fileUrl] = PendingReload(document.text, breakpoints, bookmarks)
    // Drop markers now so Document.setText() won't trigger syncToService with invalid markers.
    service.dropFileEntries(fileUrl)
  }

  override fun fileContentReloaded(file: VirtualFile, document: Document) {
    val fileUrl = file.url
    val pending = pendingReloads.remove(fileUrl) ?: return
    val newContent = document.text
    for ((def, currentLine) in pending.breakpoints) {
      val targetLine = translateLine(pending.oldContent, newContent, currentLine) ?: currentLine
      if (targetLine != def.line) {
        service.moveBreakpointLine(def, targetLine)
      }
    }
    for ((def, currentLine) in pending.bookmarks) {
      val targetLine = translateLine(pending.oldContent, newContent, currentLine) ?: currentLine
      if (targetLine != def.line) {
        service.moveBookmarkLine(def, targetLine)
      }
    }
    service.onFileOpened(file)
  }

  private fun translateLine(oldContent: String, newContent: String, line: Int): Int? {
    return try {
      val result = Diff.translateLine(oldContent, newContent, line, false)
      if (result >= 0) result else null
    }
    catch (_: FilesTooBigForDiffException) {
      null
    }
  }
}
