package com.intellij.debugmap.listener

import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.ide.bookmark.Bookmark
import com.intellij.ide.bookmark.BookmarkGroup
import com.intellij.ide.bookmark.BookmarkType
import com.intellij.ide.bookmark.BookmarksListener
import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.LineBookmark
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XLineBreakpoint

/** Keeps [DebugMapService] in sync with IDE breakpoint and bookmark lifecycle events. */
class DebugMapBreakpointListener(private val project: Project) : XBreakpointListener<XBreakpoint<*>>, BookmarksListener {

  private val service get() = DebugMapService.getInstance(project)

  override fun breakpointAdded(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    val activeGroupId = service.getActiveGroupId() ?: return
    service.upsertBreakpointInGroup(activeGroupId, breakpoint.toDef(activeGroupId))
  }

  override fun breakpointRemoved(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    val activeGroupId = service.getActiveGroupId() ?: return
    service.removeBreakpointFromGroup(activeGroupId, breakpoint.fileUrl, breakpoint.line)
  }

  override fun breakpointChanged(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    val activeGroupId = service.getActiveGroupId() ?: return

    // Fast path: position unchanged, only properties (condition, log, etc.) changed.
    if (service.isGroupBreakpoint(breakpoint.fileUrl, breakpoint.line)) {
      service.upsertBreakpointInGroup(activeGroupId, breakpoint.toDef(activeGroupId))
      return
    }

    // Line changed (code inserted/deleted): the stored def still has the old line.
    // Find it by checking which stored line in this file is no longer in the IDE.
    val ideLinesInFile = XDebuggerManager.getInstance(project).breakpointManager
      .allBreakpoints
      .filterIsInstance<XLineBreakpoint<*>>()
      .filter { it.fileUrl == breakpoint.fileUrl }
      .mapTo(HashSet()) { it.line }
    val staleDef = service.getGroupBreakpoints(activeGroupId)
                     .firstOrNull { it.fileUrl == breakpoint.fileUrl && it.line !in ideLinesInFile }
                   ?: return
    service.removeBreakpointFromGroup(activeGroupId, staleDef.fileUrl, staleDef.line)
    service.upsertBreakpointInGroup(activeGroupId, breakpoint.toDef(activeGroupId))
  }

  private fun XLineBreakpoint<*>.toDef(groupId: Int) = BreakpointDef(
    groupId = groupId,
    fileUrl = fileUrl,
    line = line,
    typeId = type.id,
    condition = conditionExpression?.expression,
    logExpression = logExpressionObject?.expression,
  )

  // region BookmarksListener

  override fun bookmarkAdded(group: BookmarkGroup, bookmark: Bookmark) {
    if (bookmark !is LineBookmark) return
    val activeGroupId = service.getActiveGroupId() ?: return
    service.upsertBookmarkInGroup(activeGroupId, bookmark.toDef(activeGroupId, group))
  }

  override fun bookmarkRemoved(group: BookmarkGroup, bookmark: Bookmark) {
    if (bookmark !is LineBookmark) return
    val activeGroupId = service.getActiveGroupId() ?: return
    service.removeBookmarkFromGroup(activeGroupId, bookmark.file.url, bookmark.line)
  }

  override fun bookmarkChanged(group: BookmarkGroup, bookmark: Bookmark) {
    if (bookmark !is LineBookmark) return
    val activeGroupId = service.getActiveGroupId() ?: return
    service.upsertBookmarkInGroup(activeGroupId, bookmark.toDef(activeGroupId, group))
  }

  override fun bookmarkTypeChanged(bookmark: Bookmark) {
    if (bookmark !is LineBookmark) return
    val activeGroupId = service.getActiveGroupId() ?: return
    val existing = service.getGroupBookmarks(activeGroupId)
                     .firstOrNull { it.fileUrl == bookmark.file.url && it.line == bookmark.line } ?: return
    val newType = BookmarksManager.getInstance(project)?.getType(bookmark) ?: BookmarkType.DEFAULT
    service.upsertBookmarkInGroup(activeGroupId, existing.copy(type = newType))
  }

  private fun LineBookmark.toDef(groupId: Int, bookmarkGroup: BookmarkGroup) = BookmarkDef(
    groupId = groupId,
    fileUrl = file.url,
    line = line,
    name = bookmarkGroup.getDescription(this),
    type = BookmarksManager.getInstance(project)?.getType(this) ?: BookmarkType.DEFAULT,
  )

  // endregion
}
