package com.intellij.debugmap.sync

import com.intellij.debugmap.model.BreakpointDef
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.impl.breakpoints.XBreakpointManagerImpl

/**
 * Centralizes all interactions with [com.intellij.xdebugger.breakpoints.XBreakpointManager].
 *
 * Read operations must be called inside a read action.
 * Write operations must be called inside a write action.
 */
class BreakpointIdeManager(private val project: Project) {

  private val bpManager get() = XDebuggerManager.getInstance(project).breakpointManager

  private val depManager get() = (bpManager as XBreakpointManagerImpl).dependentBreakpointManager

  // ── Read operations ────────────────────────────────────────────────────────

  fun allLineBreakpoints(): List<XLineBreakpoint<*>> {
    return bpManager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
  }

  fun findLineBreakpoint(fileUrl: String, lineZeroBased: Int): XLineBreakpoint<*>? {
    return allLineBreakpoints().firstOrNull { it.fileUrl == fileUrl && it.line == lineZeroBased }
  }

  fun canPutAt(file: VirtualFile, lineZeroBased: Int): Boolean {
    return XDebuggerUtil.getInstance().getLineBreakpointTypes().any { it.canPutAt(file, lineZeroBased, project) }
  }

  // ── Write operations ───────────────────────────────────────────────────────

  /**
   * Adds a line breakpoint, applying optional condition/logExpression from [def].
   * Returns the created breakpoint, or null if no suitable type exists for this location.
   */
  fun addLineBreakpoint(file: VirtualFile, lineZeroBased: Int, def: BreakpointDef? = null): XLineBreakpoint<*>? {
    @Suppress("UNCHECKED_CAST")
    val type = XDebuggerUtil.getInstance().getLineBreakpointTypes()
                 .filter { it.canPutAt(file, lineZeroBased, project) }
                 .maxByOrNull { it.priority }
                 as? XLineBreakpointType<XBreakpointProperties<*>>
               ?: return null

    val properties = type.createBreakpointProperties(file, lineZeroBased)
    val bp = bpManager.addLineBreakpoint(type, file.url, lineZeroBased, properties)
    def?.condition?.let { bp.setCondition(it) }
    def?.logExpression?.let { bp.setLogExpression(it) }
    return bp
  }

  fun removeBreakpoint(bp: XLineBreakpoint<*>) {
    bpManager.removeBreakpoint(bp)
  }

  // ── Batch operations (used by checkout) ───────────────────────────────────

  fun addBreakpointDefs(breakpointDefs: List<BreakpointDef>) {
    val vfManager = VirtualFileManager.getInstance()
    for (breakpointDef in breakpointDefs) {
      val file = vfManager.findFileByUrl(breakpointDef.fileUrl) ?: continue
      if (findLineBreakpoint(breakpointDef.fileUrl, breakpointDef.line) != null) continue
      addLineBreakpoint(file, breakpointDef.line, breakpointDef)
    }
  }

  fun removeBreakpointDefs(breakpointDefs: List<BreakpointDef>) {
    val existing = allLineBreakpoints()
    for (breakpointDef in breakpointDefs) {
      existing.firstOrNull { it.fileUrl == breakpointDef.fileUrl && it.line == breakpointDef.line }
        ?.let { bpManager.removeBreakpoint(it) }
    }
  }

  fun getMasterBreakpoint(bp: XLineBreakpoint<*>): XLineBreakpoint<*>? {
    return depManager?.getMasterBreakpoint(bp) as? XLineBreakpoint<*>
  }

  fun isLeaveEnabled(bp: XLineBreakpoint<*>): Boolean {
    return depManager?.isLeaveEnabled(bp) ?: true
  }

  fun setMasterBreakpoint(slave: XLineBreakpoint<*>, master: XLineBreakpoint<*>, leaveEnabled: Boolean) {
    depManager?.setMasterBreakpoint(slave, master, leaveEnabled)
  }

  fun clearMasterBreakpoint(bp: XLineBreakpoint<*>) {
    depManager?.clearMasterBreakpoint(bp)
  }

  fun setDefaultGroup(groupId: Int?) {
    (bpManager as? XBreakpointManagerImpl)?.defaultGroup = if (groupId != null) "group:$groupId" else null
  }
}
