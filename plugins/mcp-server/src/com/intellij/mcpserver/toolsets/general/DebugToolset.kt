@file:Suppress("FunctionName", "unused")

package com.intellij.mcpserver.toolsets.general

import com.intellij.debugger.ui.breakpoints.JavaExceptionBreakpointType
import com.intellij.mcpserver.McpServerBundle
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.reportToolActivity
import com.intellij.mcpserver.toolsets.Constants
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import org.jetbrains.java.debugger.breakpoints.properties.JavaExceptionBreakpointProperties

class DebugToolset : McpToolset {

  @McpTool
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
  ): BreakpointResult {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.setting.breakpoint", path, line))
    val project = currentCoroutineContext().project

    val resolvedPath = project.resolveInProject(path)
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolvedPath)
               ?: mcpFail(McpServerBundle.message("tool.error.file.not.found", path))

    val lineZeroBased = line - 1

    return readAction {
      val manager = XDebuggerManager.getInstance(project).breakpointManager

      // Check if a breakpoint already exists at this location
      val existing = manager.allBreakpoints
        .filterIsInstance<XLineBreakpoint<*>>()
        .firstOrNull { it.fileUrl == file.url && it.line == lineZeroBased }

      if (existing != null) {
        return@readAction BreakpointResult(path = path, line = line, status = "already_exists")
      }

      // Verify that at least one breakpoint type supports this location
      val canPut = XDebuggerUtil.getInstance().getLineBreakpointTypes()
        .any { it.canPutAt(file, lineZeroBased, project) }
      if (!canPut) {
        mcpFail(McpServerBundle.message("tool.error.cannot.set.breakpoint", path, line))
      }

      // toggleLineBreakpoint is a Java method with no exposed generic parameter —
      // it handles type-safe addLineBreakpoint internally. Since we confirmed above
      // that no breakpoint exists here, toggle = add.
      XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file, lineZeroBased)
      BreakpointResult(path = path, line = line, status = "created")
    }
  }

  @McpTool
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

    return readAction {
      val manager = XDebuggerManager.getInstance(project).breakpointManager
      val targets = manager.allBreakpoints
        .filterIsInstance<XLineBreakpoint<*>>()
        .filter { it.fileUrl == file.url && it.line == lineZeroBased }

      if (targets.isEmpty()) {
        return@readAction BreakpointResult(path = path, line = line, status = "not_found")
      }

      targets.forEach { manager.removeBreakpoint(it) }
      BreakpointResult(path = path, line = line, status = "removed")
    }
  }

  @McpTool
  @McpDescription("""
    |Updates properties of an existing line breakpoint at the specified location.
    |Only the parameters you provide are changed; omitted parameters keep their current value.
    |
    |For condition and logExpression:
    |  - Omit the parameter (pass null) to leave the value unchanged.
    |  - Pass an empty string "" to clear the existing value.
    |  - Pass a non-empty string to set a new expression.
    |
    |suspendPolicy values:
    |  "ALL"    — suspend all threads (default breakpoint behaviour)
    |  "THREAD" — suspend only the thread that hit the breakpoint
    |  "NONE"   — do not suspend; useful for logging breakpoints / tracepoints
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

    return readAction {
      val manager = XDebuggerManager.getInstance(project).breakpointManager
      val bp = manager.allBreakpoints
        .filterIsInstance<XLineBreakpoint<*>>()
        .firstOrNull { it.fileUrl == file.url && it.line == lineZeroBased }
        ?: return@readAction BreakpointResult(path = path, line = line, status = "not_found")

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

      BreakpointResult(path = path, line = line, status = "updated")
    }
  }

  @McpTool
  @McpDescription("""
    |Lists all line breakpoints currently set in the project.
    |Returns file path (relative to project root), 1-based line number, enabled state,
    |condition expression, log expression, log-message flag, and suspend policy.
  """)
  suspend fun list_breakpoints(): BreakpointListResult {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.listing.breakpoints"))
    val project = currentCoroutineContext().project

    return readAction {
      val manager = XDebuggerManager.getInstance(project).breakpointManager
      val projectDir = project.basePath ?: ""

      val items = manager.allBreakpoints
        .filterIsInstance<XLineBreakpoint<*>>()
        .map { bp ->
          val absolutePath = bp.presentableFilePath
          val relativePath = if (projectDir.isNotEmpty() && absolutePath.startsWith(projectDir)) {
            absolutePath.removePrefix(projectDir).trimStart('/', '\\')
          } else {
            absolutePath
          }
          BreakpointInfo(
            path = relativePath,
            line = bp.line + 1,
            enabled = bp.isEnabled,
            condition = bp.conditionExpression?.expression?.takeIf { it.isNotBlank() },
            logExpression = bp.logExpressionObject?.expression?.takeIf { it.isNotBlank() },
            logMessage = bp.isLogMessage,
            suspendPolicy = bp.suspendPolicy.name,
          )
        }

      BreakpointListResult(breakpoints = items, total = items.size)
    }
  }

  @McpTool
  @McpDescription("""
    |Sets a Java exception breakpoint that fires when the specified exception class is thrown.
    |If a breakpoint for the same exception already exists, updates its properties instead.
    |Requires Java language support in the IDE.
    |
    |Use exceptionClass "any" (or omit it) to match any thrown exception.
    |Set suspendPolicy to "NONE" together with a logExpression to create a non-suspending
    |exception tracepoint that only logs.
  """)
  suspend fun set_exception_breakpoint(
    @McpDescription("Fully qualified exception class name, e.g. 'java.lang.NullPointerException'. Use 'any' or omit to match any throwable.")
    exceptionClass: String? = null,
    @McpDescription("Trigger when the exception is caught by a try/catch block (default: true).")
    notifyCaught: Boolean = true,
    @McpDescription("Trigger when the exception is not caught and propagates up (default: true).")
    notifyUncaught: Boolean = true,
    @McpDescription("Optional condition expression that must evaluate to true for the breakpoint to fire.")
    condition: String? = null,
  ): ExceptionBreakpointResult {
    val normalizedClass = exceptionClass?.takeIf { it.isNotBlank() && it != "any" }
    val displayClass = normalizedClass ?: "any"

    currentCoroutineContext().reportToolActivity(
      McpServerBundle.message("tool.activity.setting.exception.breakpoint", displayClass))
    val project = currentCoroutineContext().project

    return readAction {
      val manager = XDebuggerManager.getInstance(project).breakpointManager

      @Suppress("UnstableApiUsage")
      val exceptionType = XBreakpointType.EXTENSION_POINT_NAME.extensionList
        .filterIsInstance<JavaExceptionBreakpointType>()
        .firstOrNull()
        ?: mcpFail(McpServerBundle.message("tool.error.java.exception.breakpoints.not.available"))

      // Reuse an existing breakpoint with the same class rather than creating a duplicate
      @Suppress("UNCHECKED_CAST")
      val existing = manager.allBreakpoints
        .filter { it.type is JavaExceptionBreakpointType }
        .map { it as XBreakpoint<JavaExceptionBreakpointProperties> }
        .firstOrNull { it.properties.myQualifiedName == normalizedClass }

      val bp: XBreakpoint<JavaExceptionBreakpointProperties>
      val status: String
      if (existing != null) {
        bp = existing
        status = "already_exists"
      } else {
        val props = if (normalizedClass != null) {
          JavaExceptionBreakpointProperties(normalizedClass)
        } else {
          JavaExceptionBreakpointProperties()
        }
        bp = manager.addBreakpoint(exceptionType, props)
        status = "created"
      }

      bp.properties.NOTIFY_CAUGHT = notifyCaught
      bp.properties.NOTIFY_UNCAUGHT = notifyUncaught
      condition?.let { bp.setCondition(it.ifBlank { null }) }

      ExceptionBreakpointResult(exceptionClass = displayClass, status = status)
    }
  }

  @McpTool
  @McpDescription("""
    |Removes a Java exception breakpoint for the given exception class.
    |Use exceptionClass "any" (or omit it) to target the catch-all exception breakpoint.
  """)
  suspend fun remove_exception_breakpoint(
    @McpDescription("Fully qualified exception class name, or 'any' to remove the catch-all exception breakpoint.")
    exceptionClass: String? = null,
  ): ExceptionBreakpointResult {
    val normalizedClass = exceptionClass?.takeIf { it.isNotBlank() && it != "any" }
    val displayClass = normalizedClass ?: "any"

    currentCoroutineContext().reportToolActivity(
      McpServerBundle.message("tool.activity.removing.exception.breakpoint", displayClass))
    val project = currentCoroutineContext().project

    return readAction {
      val manager = XDebuggerManager.getInstance(project).breakpointManager

      @Suppress("UNCHECKED_CAST")
      val targets = manager.allBreakpoints
        .filter { it.type is JavaExceptionBreakpointType }
        .map { it as XBreakpoint<JavaExceptionBreakpointProperties> }
        .filter { it.properties.myQualifiedName == normalizedClass }

      if (targets.isEmpty()) {
        return@readAction ExceptionBreakpointResult(exceptionClass = displayClass, status = "not_found")
      }

      targets.forEach { manager.removeBreakpoint(it) }
      ExceptionBreakpointResult(exceptionClass = displayClass, status = "removed")
    }
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
    val logMessage: Boolean = false,
    /** "ALL" | "THREAD" | "NONE" */
    val suspendPolicy: String = "ALL",
  )

  @Serializable
  data class BreakpointListResult(
    val breakpoints: List<BreakpointInfo>,
    val total: Int,
  )

  @Serializable
  data class ExceptionBreakpointResult(
    val exceptionClass: String,
    /** "created" | "already_exists" | "removed" | "not_found" */
    val status: String,
  )
}
