package com.intellij.debugmap.sync

import com.intellij.debugmap.DebugMapService
import com.intellij.openapi.project.Project

/** Syncs IDE breakpoints on checkout. Must be called on EDT inside a write action. */
class BreakpointIdeSyncer(private val project: Project) {

  private val service get() = DebugMapService.getInstance(project)
  private val ideManager = BreakpointIdeManager(project)

  fun checkout(targetGroupId: Int?) {
    val currentGroupId = service.getActiveGroupId()

    // Null out first so breakpointRemoved events are ignored.
    service.setActiveGroupId(null)
    ideManager.setDefaultGroup(null)
    if (currentGroupId != null) {
      ideManager.removeBreakpointDefs(service.getGroupBreakpoints(currentGroupId))
    }

    // Set target before adding so breakpointAdded events sync to the right group.
    service.setActiveGroupId(targetGroupId)
    ideManager.setDefaultGroup(targetGroupId)
    if (targetGroupId != null) {
      ideManager.addBreakpointDefs(service.getGroupBreakpoints(targetGroupId))
    }
  }
}
