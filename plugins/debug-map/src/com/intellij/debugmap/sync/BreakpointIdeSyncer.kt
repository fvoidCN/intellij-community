package com.intellij.debugmap.sync

import com.intellij.debugmap.DebugMapService
import com.intellij.openapi.project.Project

/**
 * Performs the actual IDE ↔ service synchronization for checkout operations.
 *
 * All public methods must be called on the EDT inside a write action, as
 * XBreakpointManager mutations are UI-thread operations.
 *
 * Instead of suppressing listener events with a flag, this syncer controls
 * [DebugMapService.setActiveGroupIdQuiet] to drive listener behaviour:
 *
 * - During the remove phase: activeGroupId is set to null, so
 *   [com.intellij.debugmap.listener.DebugMapBreakpointListener.breakpointRemoved]
 *   ignores the events and stored data is left intact.
 *
 * - During the add phase: activeGroupId is set to [targetGroupId], so
 *   [com.intellij.debugmap.listener.DebugMapBreakpointListener.breakpointAdded]
 *   fires normally and re-syncs each def (including the actual typeId chosen by
 *   IntelliJ for the current code position) back into the service.
 */
class BreakpointIdeSyncer(private val project: Project) {

  private val service get() = DebugMapService.getInstance(project)
  private val ideManager = BreakpointIdeManager(project)

  /**
   * Switches the active group to [targetGroupId] (or null = no active group):
   * 1. Nulls out activeGroupId so removes are ignored by the listener.
   * 2. Removes the current active group's breakpoints from the IDE.
   * 3. Sets activeGroupId to [targetGroupId] so adds are captured by the listener.
   * 4. Adds [targetGroupId]'s breakpoints to the IDE — the listener re-syncs
   *    each def with the actual typeId back into the service automatically.
   */
  fun checkout(targetGroupId: Int?) {
    val currentGroupId = service.getActiveGroupId()

    // Null out first so breakpointRemoved events are ignored.
    service.setActiveGroupIdQuiet(null)
    ideManager.setDefaultGroup(null)
    if (currentGroupId != null) {
      ideManager.removeBreakpointDefs(service.getGroupBreakpoints(currentGroupId))
    }

    // Set target before adding so breakpointAdded events sync to the right group.
    service.setActiveGroupIdQuiet(targetGroupId)
    ideManager.setDefaultGroup(targetGroupId)
    if (targetGroupId != null) {
      ideManager.addBreakpointDefs(service.getGroupBreakpoints(targetGroupId))
    }
  }
}
