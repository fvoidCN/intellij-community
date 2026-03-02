package com.intellij.debugmap.model

/**
 * A breakpoint definition owned by a group.
 * [line] is 0-based (matching [com.intellij.xdebugger.breakpoints.XLineBreakpoint.getLine]).
 * The breakpoint type is inferred at restore time via canPutAt, not stored explicitly.
 */
data class BreakpointDef(
    val fileUrl: String,
    val line: Int,
    val condition: String? = null,
    val logExpression: String? = null,
    val annotation: String? = null,
)

