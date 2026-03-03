package com.intellij.debugmap.listener

import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XLineBreakpoint

/** Keeps [DebugMapService] in sync with IDE breakpoint lifecycle events. */
class DebugMapBreakpointListener(private val project: Project) : XBreakpointListener<XBreakpoint<*>> {

  private val service get() = DebugMapService.getInstance(project)

  override fun breakpointAdded(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    val activeGroupId = service.getActiveGroupId() ?: return
    service.addBreakpointToGroup(activeGroupId, breakpoint.toDef())
  }

  override fun breakpointRemoved(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    if (service.getActiveGroupId() == null) return
    val groupId = service.getBreakpointGroupId(breakpoint.fileUrl, breakpoint.line) ?: return
    service.removeBreakpointFromGroup(groupId, breakpoint.fileUrl, breakpoint.line)
  }

  override fun breakpointChanged(breakpoint: XBreakpoint<*>) {
    if (breakpoint !is XLineBreakpoint<*>) return
    if (service.getActiveGroupId() == null) return
    val groupId = service.getBreakpointGroupId(breakpoint.fileUrl, breakpoint.line) ?: return
    service.addBreakpointToGroup(groupId, breakpoint.toDef())
  }

  private fun XLineBreakpoint<*>.toDef() = BreakpointDef(
    fileUrl = fileUrl,
    line = line,
    typeId = type.id,
    condition = conditionExpression?.expression,
    logExpression = logExpressionObject?.expression,
  )
}
