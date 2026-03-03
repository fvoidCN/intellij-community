package com.intellij.debugmap.ui

import com.intellij.debugmap.model.BreakpointDef

internal sealed class DebugMapNode {
  data class Group(val id: Int, val name: String, val isActive: Boolean) : DebugMapNode()
  data class BreakpointItem(val groupId: Int, val def: BreakpointDef) : DebugMapNode()
}
