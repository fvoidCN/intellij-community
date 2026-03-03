package com.intellij.debugmap.manager

import com.intellij.debugmap.model.BreakpointDef

class BreakpointDefManager {

  private val groupBreakpoints = mutableMapOf<Int, MutableList<BreakpointDef>>()

  fun initGroup(groupId: Int) {
    groupBreakpoints.getOrPut(groupId) { mutableListOf() }
  }

  fun getGroupBreakpoints(groupId: Int): List<BreakpointDef> =
    groupBreakpoints[groupId]?.toList() ?: emptyList()

  /**
   * Adds or replaces a breakpoint definition in [groupId].
   * Uniqueness key is (fileUrl, line) within the group.
   */
  fun addBreakpointToGroup(groupId: Int, def: BreakpointDef) {
    val list = groupBreakpoints.getOrPut(groupId) { mutableListOf() }
    val idx = list.indexOfFirst { it.fileUrl == def.fileUrl && it.line == def.line }
    if (idx >= 0) list[idx] = def else list.add(def)
  }

  fun removeBreakpointFromGroup(groupId: Int, fileUrl: String, line: Int) {
    groupBreakpoints[groupId]?.removeIf { it.fileUrl == fileUrl && it.line == line }
  }

  /** True if (fileUrl, line) is owned by any group. */
  fun isGroupBreakpoint(fileUrl: String, line: Int): Boolean =
    groupBreakpoints.values.any { list -> list.any { it.fileUrl == fileUrl && it.line == line } }

  /** Returns the group ID that owns (fileUrl, line), or null if none. */
  fun getBreakpointGroupId(fileUrl: String, line: Int): Int? =
    groupBreakpoints.entries
      .firstOrNull { (_, list) -> list.any { it.fileUrl == fileUrl && it.line == line } }
      ?.key

  fun removeGroup(groupId: Int) {
    groupBreakpoints.remove(groupId)
  }

  fun getAllGroupBreakpoints(): Map<Int, List<BreakpointDef>> =
    groupBreakpoints.mapValues { it.value.toList() }

  fun restore(snapshot: Map<Int, List<BreakpointDef>>) {
    groupBreakpoints.clear()
    snapshot.forEach { (groupId, defs) ->
      groupBreakpoints[groupId] = defs.toMutableList()
    }
  }
}
