package com.intellij.debugmap.model

/**
 * A breakpoint definition owned by a group.
 * [line] is 0-based (matching [com.intellij.xdebugger.breakpoints.XLineBreakpoint.getLine]).
 */
data class BreakpointDef(
  val fileUrl: String,
  val line: Int,
  val typeId: String = "java-line",
  val condition: String? = null,
  val logExpression: String? = null,
  val annotation: String? = null,
)
