package com.intellij.debugmap.listener

import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XLineBreakpoint

/** Keeps [DebugMapService] in sync with IDE breakpoint lifecycle events. */
class DebugMapBreakpointListener(private val project: Project) : XBreakpointListener<XBreakpoint<*>> {

  private val service get() = DebugMapService.getInstance(project)

  override fun breakpointAdded(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    val activeGroupId = service.getActiveGroupId() ?: return
    service.upsertBreakpointInGroup(activeGroupId, breakpoint.toDef())
  }

  override fun breakpointRemoved(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    if (service.getActiveGroupId() == null) return
    val groupId = service.getBreakpointGroupId(breakpoint.fileUrl, breakpoint.line) ?: return
    service.removeBreakpointFromGroup(groupId, breakpoint.fileUrl, breakpoint.line)
  }

  override fun breakpointChanged(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    val activeGroupId = service.getActiveGroupId() ?: return

    // Fast path: position unchanged, only properties (condition, log, etc.) changed.
    val groupId = service.getBreakpointGroupId(breakpoint.fileUrl, breakpoint.line)
    if (groupId != null) {
      service.upsertBreakpointInGroup(groupId, breakpoint.toDef())
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
    service.upsertBreakpointInGroup(activeGroupId, breakpoint.toDef())
  }

  private fun XLineBreakpoint<*>.toDef() = BreakpointDef(
    fileUrl = fileUrl,
    line = line,
    typeId = type.id,
    condition = conditionExpression?.expression,
    logExpression = logExpressionObject?.expression,
  )
}
