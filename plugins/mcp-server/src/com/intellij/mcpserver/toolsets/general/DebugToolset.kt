@file:Suppress("FunctionName", "unused")

package com.intellij.mcpserver.toolsets.general

import com.intellij.mcpserver.McpServerBundle
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.reportToolActivity
import com.intellij.mcpserver.toolsets.Constants
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.mcpserver.util.relativizeIfPossible
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import java.nio.file.Path
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.impl.breakpoints.XBreakpointManagerImpl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class DebugToolset : McpToolset {

  @McpTool(name = "debug_set_breakpoint")
  @McpDescription("""
    |Sets a line breakpoint at the specified file and line.
    |If a breakpoint already exists at that location, reports it without creating a duplicate.
    |
    |The breakpoint appears immediately as a red dot in the editor gutter.
    |It will be hit during the next debug session when execution reaches that line.
  """)
  suspend fun set_breakpoint(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    path: String,
    @McpDescription("1-based line number where the breakpoint should be set")
    line: Int,
    @McpDescription("Source text of the line where the breakpoint should be set")
    content: String,
  ): BreakpointResult {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.setting.breakpoint", path, line))
    val project = currentCoroutineContext().project

    val resolvedPath = project.resolveInProject(path)
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolvedPath)
               ?: mcpFail(McpServerBundle.message("tool.error.file.not.found", path))

    val lineZeroBased = line - 1

    val alreadyExists = readAction {
      val actual = lineContent(file, lineZeroBased)
      if (actual == null || actual.trim() != content.trim()) {
        mcpFail("Line $line contains '${actual ?: ""}', not '${content}'. Re-read the file and pass the exact source text of the target line.")
      }

      val manager = XDebuggerManager.getInstance(project).breakpointManager

      // Check if a breakpoint already exists at this location
      val existing = manager.allBreakpoints
        .filterIsInstance<XLineBreakpoint<*>>()
        .firstOrNull { it.fileUrl == file.url && it.line == lineZeroBased }

      if (existing != null) return@readAction true

      // Verify that at least one breakpoint type supports this location
      val canPut = XDebuggerUtil.getInstance().getLineBreakpointTypes()
        .any { it.canPutAt(file, lineZeroBased, project) }
      if (!canPut) {
        mcpFail(McpServerBundle.message("tool.error.cannot.set.breakpoint", path, line))
      }

      false
    }

    if (alreadyExists) {
      return BreakpointResult(path = path, line = line, status = "already_exists")
    }

    // toggleLineBreakpoint is a Java method with no exposed generic parameter —
    // it handles type-safe addLineBreakpoint internally. Since we confirmed above
    // that no breakpoint exists here, toggle = add.
    writeAction {
      XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file, lineZeroBased)
    }

    return BreakpointResult(path = path, line = line, status = "created")
  }

  @McpTool(name = "debug_remove_breakpoint")
  @McpDescription("""
    |Removes the breakpoint at the specified file and line.
    |If no breakpoint exists at that location, reports accordingly.
  """)
  suspend fun remove_breakpoint(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    path: String,
    @McpDescription("1-based line number of the breakpoint to remove")
    line: Int,
  ): BreakpointResult {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.removing.breakpoint", path, line))
    val project = currentCoroutineContext().project

    val resolvedPath = project.resolveInProject(path)
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolvedPath)
               ?: mcpFail(McpServerBundle.message("tool.error.file.not.found", path))

    val lineZeroBased = line - 1

    val targets = readAction {
      val manager = XDebuggerManager.getInstance(project).breakpointManager
      manager.allBreakpoints
        .filterIsInstance<XLineBreakpoint<*>>()
        .filter { it.fileUrl == file.url && it.line == lineZeroBased }
    }

    if (targets.isEmpty()) {
      return BreakpointResult(path = path, line = line, status = "not_found")
    }

    writeAction {
      val manager = XDebuggerManager.getInstance(project).breakpointManager
      targets.forEach { manager.removeBreakpoint(it) }
    }

    return BreakpointResult(path = path, line = line, status = "removed")
  }

  @McpTool(name = "debug_update_breakpoint")
  @McpDescription("""
    |Updates properties of an existing line breakpoint at the specified location.
    |Only the parameters you provide are changed; omitted parameters keep their current value.
  """)
  suspend fun update_breakpoint(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    path: String,
    @McpDescription("1-based line number of the breakpoint to update")
    line: Int,
    @McpDescription("Enable or disable the breakpoint")
    enabled: Boolean? = null,
    @McpDescription("Condition expression that must evaluate to true for the breakpoint to fire. Pass empty string to clear.")
    condition: String? = null,
    @McpDescription("Expression to evaluate and log to the console when the breakpoint is hit. Pass empty string to clear.")
    logExpression: String? = null,
    @McpDescription("Log a standard hit notification message to the console when the breakpoint is reached.")
    logMessage: Boolean? = null,
    @McpDescription("Log a full call-stack trace to the console when the breakpoint is reached.")
    logStack: Boolean? = null,
    @McpDescription("Suspend policy: ALL, THREAD, or NONE.")
    suspendPolicy: String? = null,
  ): BreakpointResult {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.updating.breakpoint", path, line))
    val project = currentCoroutineContext().project

    val resolvedPath = project.resolveInProject(path)
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolvedPath)
               ?: mcpFail(McpServerBundle.message("tool.error.file.not.found", path))

    val lineZeroBased = line - 1

    val bp = readAction {
      val manager = XDebuggerManager.getInstance(project).breakpointManager
      manager.allBreakpoints
        .filterIsInstance<XLineBreakpoint<*>>()
        .firstOrNull { it.fileUrl == file.url && it.line == lineZeroBased }
    } ?: return BreakpointResult(path = path, line = line, status = "not_found")

    writeAction {
      enabled?.let { bp.setEnabled(it) }
      condition?.let { bp.setCondition(it.ifBlank { null }) }
      logExpression?.let { bp.setLogExpression(it.ifBlank { null }) }
      logMessage?.let { bp.setLogMessage(it) }
      logStack?.let { bp.setLogStack(it) }
      suspendPolicy?.let {
        bp.setSuspendPolicy(when (it.uppercase()) {
          "ALL" -> SuspendPolicy.ALL
          "THREAD" -> SuspendPolicy.THREAD
          "NONE" -> SuspendPolicy.NONE
          else -> mcpFail(McpServerBundle.message("tool.error.invalid.suspend.policy", it))
        })
      }
    }

    return BreakpointResult(path = path, line = line, status = "updated")
  }

  @McpTool(name = "debug_list_breakpoints")
  @McpDescription("""
    |Lists all line breakpoints currently set in the project.
    |Returns file path (relative to project root), 1-based line number, enabled state,
    |condition expression, log expression, log-message flag, suspend policy,
    |the source text of the breakpoint line, and any master-breakpoint dependency.
  """)
  suspend fun list_breakpoints(): BreakpointListResult {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.listing.breakpoints"))
    val project = currentCoroutineContext().project

    return readAction {
      val manager = XDebuggerManager.getInstance(project).breakpointManager
      val depMgr = (manager as XBreakpointManagerImpl).dependentBreakpointManager
      val projectPath = project.basePath?.let { Path.of(it) }

      val items = manager.allBreakpoints
        .filterIsInstance<XLineBreakpoint<*>>()
        .map { bp ->
          val vf = VirtualFileManager.getInstance().findFileByUrl(bp.fileUrl)
          val masterBp = depMgr.getMasterBreakpoint(bp) as? XLineBreakpoint<*>
          val masterVf = masterBp?.let { VirtualFileManager.getInstance().findFileByUrl(it.fileUrl) }
          BreakpointInfo(
            path = if (vf != null && projectPath != null) projectPath.relativizeIfPossible(vf) else bp.presentableFilePath,
            line = bp.line + 1,
            enabled = bp.isEnabled,
            condition = bp.conditionExpression?.expression?.takeIf { it.isNotBlank() },
            logExpression = bp.logExpressionObject?.expression?.takeIf { it.isNotBlank() },
            logMessage = bp.isLogMessage,
            suspendPolicy = bp.suspendPolicy.name,
            content = vf?.let { lineContent(it, bp.line) },
            dependsOnPath = masterBp?.let {
              if (masterVf != null && projectPath != null) projectPath.relativizeIfPossible(masterVf) else it.presentableFilePath
            },
            dependsOnLine = masterBp?.let { it.line + 1 },
            dependencyLeaveEnabled = masterBp?.let { depMgr.isLeaveEnabled(bp) },
          )
        }

      BreakpointListResult(breakpoints = items, total = items.size)
    }
  }

  @McpTool(name = "debug_set_breakpoint_dependency")
  @McpDescription("""
    |Makes a line breakpoint (the "slave") inactive until another line breakpoint (the "master") has been hit.
    |Both breakpoints must already exist.
  """)
  suspend fun set_breakpoint_dependency(
    @McpDescription("Path of the slave breakpoint (the one that should only fire after the master)")
    slavePath: String,
    @McpDescription("1-based line number of the slave breakpoint")
    slaveLine: Int,
    @McpDescription("Path of the master breakpoint (the one that must be hit first)")
    masterPath: String,
    @McpDescription("1-based line number of the master breakpoint")
    masterLine: Int,
    @McpDescription("If true (default), slave stays permanently enabled after master fires. If false, slave fires once then disables itself.")
    leaveEnabled: Boolean = true,
  ): BreakpointDependencyResult {
    currentCoroutineContext().reportToolActivity(
      McpServerBundle.message("tool.activity.setting.breakpoint.dependency", slavePath, slaveLine, masterPath, masterLine))
    val project = currentCoroutineContext().project

    val slaveFile = project.resolveInProject(slavePath).let { p ->
      LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)
        ?: mcpFail(McpServerBundle.message("tool.error.file.not.found", slavePath))
    }
    val masterFile = project.resolveInProject(masterPath).let { p ->
      LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)
        ?: mcpFail(McpServerBundle.message("tool.error.file.not.found", masterPath))
    }

    val slaveLineZeroBased = slaveLine - 1
    val masterLineZeroBased = masterLine - 1

    data class FoundBreakpoints(val slave: XLineBreakpoint<*>?, val master: XLineBreakpoint<*>?)

    val found = readAction {
      val manager = XDebuggerManager.getInstance(project).breakpointManager
      val all = manager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
      FoundBreakpoints(
        slave = all.firstOrNull { it.fileUrl == slaveFile.url && it.line == slaveLineZeroBased },
        master = all.firstOrNull { it.fileUrl == masterFile.url && it.line == masterLineZeroBased },
      )
    }

    if (found.slave == null) {
      return BreakpointDependencyResult(slavePath, slaveLine, masterPath, masterLine, "slave_not_found")
    }
    if (found.master == null) {
      return BreakpointDependencyResult(slavePath, slaveLine, masterPath, masterLine, "master_not_found")
    }

    writeAction {
      val manager = XDebuggerManager.getInstance(project).breakpointManager
      (manager as XBreakpointManagerImpl).dependentBreakpointManager
        .setMasterBreakpoint(found.slave, found.master, leaveEnabled)
    }

    return BreakpointDependencyResult(slavePath, slaveLine, masterPath, masterLine, "set")
  }

  @McpTool(name = "debug_clear_breakpoint_dependency")
  @McpDescription("""
    |Removes the master-breakpoint dependency from a slave breakpoint so it fires unconditionally again.
    |Has no effect if the breakpoint has no dependency set.
  """)
  suspend fun clear_breakpoint_dependency(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    path: String,
    @McpDescription("1-based line number of the slave breakpoint")
    line: Int,
  ): BreakpointDependencyResult {
    currentCoroutineContext().reportToolActivity(
      McpServerBundle.message("tool.activity.clearing.breakpoint.dependency", path, line))
    val project = currentCoroutineContext().project

    val file = project.resolveInProject(path).let { p ->
      LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)
        ?: mcpFail(McpServerBundle.message("tool.error.file.not.found", path))
    }

    val lineZeroBased = line - 1

    data class DepState(val bp: XLineBreakpoint<*>?, val hasDependency: Boolean)

    val state = readAction {
      val manager = XDebuggerManager.getInstance(project).breakpointManager
      val depMgr = (manager as XBreakpointManagerImpl).dependentBreakpointManager
      val bp = manager.allBreakpoints
        .filterIsInstance<XLineBreakpoint<*>>()
        .firstOrNull { it.fileUrl == file.url && it.line == lineZeroBased }
      DepState(bp = bp, hasDependency = bp != null && depMgr.getMasterBreakpoint(bp) != null)
    }

    if (state.bp == null) {
      return BreakpointDependencyResult(path, line, null, null, "slave_not_found")
    }
    if (!state.hasDependency) {
      return BreakpointDependencyResult(path, line, null, null, "no_dependency")
    }

    writeAction {
      val manager = XDebuggerManager.getInstance(project).breakpointManager
      (manager as XBreakpointManagerImpl).dependentBreakpointManager
        .clearMasterBreakpoint(state.bp)
    }

    return BreakpointDependencyResult(path, line, null, null, "cleared")
  }

  // ----- data classes -----

  @Serializable
  data class BreakpointResult(
    val path: String,
    val line: Int,
    /** "created" | "already_exists" | "removed" | "not_found" | "updated" */
    val status: String,
  )

  @Serializable
  data class BreakpointInfo(
    val path: String,
    val line: Int,
    val enabled: Boolean,
    val condition: String? = null,
    val logExpression: String? = null,
    val logMessage: Boolean,
    /** "ALL" | "THREAD" | "NONE" */
    val suspendPolicy: String,
    /** Source text of the breakpoint line. */
    val content: String? = null,
    /** Path of the master breakpoint this one depends on, if any. */
    val dependsOnPath: String? = null,
    /** 1-based line of the master breakpoint this one depends on, if any. */
    val dependsOnLine: Int? = null,
    /**
     * Relevant only when dependsOnPath/dependsOnLine are set.
     * true  — this breakpoint stays permanently enabled after the master fires.
     * false — this breakpoint fires once after the master fires, then disables itself.
     */
    val dependencyLeaveEnabled: Boolean? = null,
  )

  @Serializable
  data class BreakpointListResult(
    val breakpoints: List<BreakpointInfo>,
    val total: Int,
  )

  @Serializable
  data class BreakpointDependencyResult(
    val slavePath: String,
    val slaveLine: Int,
    val masterPath: String? = null,
    val masterLine: Int? = null,
    /** "set" | "cleared" | "slave_not_found" | "master_not_found" | "no_dependency" */
    val status: String,
  )

}
