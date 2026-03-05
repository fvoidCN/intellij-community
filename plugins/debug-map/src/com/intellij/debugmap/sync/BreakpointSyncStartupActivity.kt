package com.intellij.debugmap.sync

import com.intellij.debugmap.DebugMapService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class BreakpointSyncStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    DebugMapService.getInstance(project).importFloatingBreakpoints()
  }
}
