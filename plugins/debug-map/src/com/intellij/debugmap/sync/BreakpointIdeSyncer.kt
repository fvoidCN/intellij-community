package com.intellij.debugmap.sync

import com.intellij.debugmap.DebugMapService
import com.intellij.openapi.project.Project

/**
 * Performs the actual IDE ↔ service synchronization for checkout operations.
 *
 * All public methods must be called on the EDT inside a write action, as
 * XBreakpointManager mutations are UI-thread operations.
 *
 * [DebugMapService.isSyncing] is set to true for the duration of each operation so that
 * [com.intellij.debugmap.listener.DebugMapBreakpointListener] ignores the programmatic events.
 */
class BreakpointIdeSyncer(private val project: Project) {

  private val service get() = DebugMapService.getInstance(project)
  private val ideManager = BreakpointIdeManager(project)

  /**
   * Switches the active group to [targetGroupId] (or null = no active group):
   * 1. Removes the current active group's breakpoints from the IDE.
   * 2. Sets the active group and XBreakpointManager's defaultGroup.
   * 3. Adds [targetGroupId]'s breakpoints to the IDE.
   */
  fun checkout(targetGroupId: Int?) {
    service.isSyncing = true
    try {
      val currentGroupId = service.getActiveGroupId()
      if (currentGroupId != null) {
        ideManager.removeBreakpointDefs(service.getGroupBreakpoints(currentGroupId))
      }
      // Set defaultGroup BEFORE addBreakpointDefs so that addLineBreakpoint puts
      // the restored breakpoints into the correct named group immediately.
      service.setActiveGroupId(targetGroupId)
      ideManager.setDefaultGroup(targetGroupId)
      if (targetGroupId != null) {
        ideManager.addBreakpointDefs(service.getGroupBreakpoints(targetGroupId))
      }
    }
    finally {
      service.isSyncing = false
    }
  }
}
