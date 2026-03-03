package com.intellij.debugmap.listener

import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XLineBreakpoint

/**
 * Keeps [DebugMapService] in sync with IDE breakpoint lifecycle events.
 *
 * Registered as a project-level listener via plugin.xml <projectListeners>.
 *
 * Events are suppressed when [DebugMapService.isSyncing] is true, which prevents
 * infinite loops during programmatic checkout / stash operations.
 */
class DebugMapBreakpointListener(private val project: Project) : XBreakpointListener<XBreakpoint<*>> {

  private val service get() = DebugMapService.getInstance(project)

  override fun breakpointAdded(breakpoint: XBreakpoint<*>) {
    if (service.isSyncing) return
    if (breakpoint !is XLineBreakpoint<*>) return
    val activeGroupId = service.getActiveGroupId() ?: return
    service.addBreakpointToGroup(activeGroupId, breakpoint.toDef())
  }

  override fun breakpointRemoved(breakpoint: XBreakpoint<*>) {
    if (service.isSyncing) return
    if (breakpoint !is XLineBreakpoint<*>) return
    val groupId = service.getBreakpointGroupId(breakpoint.fileUrl, breakpoint.line) ?: return
    service.removeBreakpointFromGroup(groupId, breakpoint.fileUrl, breakpoint.line)
  }

  override fun breakpointChanged(breakpoint: XBreakpoint<*>) {
    if (service.isSyncing) return
    if (breakpoint !is XLineBreakpoint<*>) return
    val groupId = service.getBreakpointGroupId(breakpoint.fileUrl, breakpoint.line) ?: return
    service.addBreakpointToGroup(groupId, breakpoint.toDef())
  }

  private fun XLineBreakpoint<*>.toDef() = BreakpointDef(
    fileUrl = fileUrl,
    line = line,
    condition = conditionExpression?.expression,
    logExpression = logExpressionObject?.expression,
  )
}
