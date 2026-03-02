package com.intellij.debugmap.sync

import com.intellij.debugmap.DebugMapService
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.impl.breakpoints.XBreakpointManagerImpl

/**
 * Performs the actual IDE ↔ service synchronization for checkout operations.
 *
 * All public methods must be called on the EDT, as XBreakpointManager mutations
 * are UI-thread operations.
 *
 * [DebugMapService.isSyncing] is set to true for the duration of each operation so that
 * [com.intellij.debugmap.listener.DebugMapBreakpointListener] ignores the programmatic events.
 */
class BreakpointIdeSyncer(private val project: Project) {

    private val service get() = DebugMapService.getInstance(project)
    private val bpManager get() = XDebuggerManager.getInstance(project).breakpointManager

    /**
     * Switches the active group to [targetGroupId] (or null = no active group):
     * 1. Removes the current active group's breakpoints from the IDE.
     * 2. Adds [targetGroupId]'s breakpoints to the IDE.
     * 3. Updates [DebugMapService.activeGroupId] and XBreakpointManager's defaultGroup.
     */
    fun checkout(targetGroupId: Int?) {
        service.isSyncing = true
        try {
            val currentGroupId = service.getActiveGroupId()
            if (currentGroupId != null) {
                removeFromIde(service.getGroupBreakpoints(currentGroupId))
            }
            // Set defaultGroup BEFORE addToIde so that addLineBreakpoint puts
            // the restored breakpoints into the correct named group immediately.
            service.setActiveGroupId(targetGroupId)
            updateDefaultGroup(targetGroupId)
            if (targetGroupId != null) {
                addToIde(service.getGroupBreakpoints(targetGroupId))
            }
        } finally {
            service.isSyncing = false
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun removeFromIde(defs: List<BreakpointDef>) {
        val allBps = bpManager.allBreakpoints
        for (def in defs) {
            allBps.filterIsInstance<XLineBreakpoint<*>>()
                .firstOrNull { it.fileUrl == def.fileUrl && it.line == def.line }
                ?.let { bpManager.removeBreakpoint(it) }
        }
    }

    private fun addToIde(defs: List<BreakpointDef>) {
        val vfManager = VirtualFileManager.getInstance()
        val xDebuggerUtil = XDebuggerUtil.getInstance()
        for (def in defs) {
            val file = vfManager.findFileByUrl(def.fileUrl) ?: continue

            // Skip if a breakpoint already exists at this position (e.g. a no-group bp)
            if (bpManager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
                    .any { it.fileUrl == def.fileUrl && it.line == def.line }) continue

            @Suppress("UNCHECKED_CAST")
            val type = xDebuggerUtil.getLineBreakpointTypes()
                .filter { it.canPutAt(file, def.line, project) }
                .maxByOrNull { it.priority }
                as? XLineBreakpointType<XBreakpointProperties<*>>
                ?: continue

            val properties = type.createBreakpointProperties(file, def.line)
            val bp = bpManager.addLineBreakpoint(type, def.fileUrl, def.line, properties)
            def.condition?.let { bp.setCondition(it) }
            def.logExpression?.let { bp.setLogExpression(it) }
        }
    }

    private fun updateDefaultGroup(groupId: Int?) {
        val impl = bpManager as? XBreakpointManagerImpl ?: return
        impl.setDefaultGroup(if (groupId != null) "debugmap:$groupId" else null)
    }
}
