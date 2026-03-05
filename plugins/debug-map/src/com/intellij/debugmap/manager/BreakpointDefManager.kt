package com.intellij.debugmap.manager

import com.intellij.debugmap.model.BreakpointDef
import java.util.TreeSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BreakpointDefManager {

  private val lock = ReentrantLock()
  private val groupBreakpoints = mutableMapOf<Int, TreeSet<BreakpointDef>>()

  fun initGroup(groupId: Int): TreeSet<BreakpointDef> = lock.withLock {
    groupBreakpoints.getOrPut(groupId) { TreeSet() }
  }

  fun getGroupBreakpoints(groupId: Int): List<BreakpointDef> = lock.withLock {
    groupBreakpoints[groupId]?.toList() ?: emptyList()
  }

  /**
   * Adds or replaces a breakpoint definition in [groupId].
   * Uniqueness key is (fileUrl, line); [annotation] from the existing entry is preserved.
   */
  fun upsertBreakpointInGroup(groupId: Int, def: BreakpointDef): Boolean = lock.withLock {
    val set = groupBreakpoints.getOrPut(groupId) { TreeSet() }
    val existing = set.floor(def)?.takeIf { it.fileUrl == def.fileUrl && it.line == def.line }
    if (existing != null) set.remove(existing)
    set.add(if (existing != null) def.copy(annotation = existing.annotation) else def)
  }

  fun removeBreakpointFromGroup(groupId: Int, fileUrl: String, line: Int): Boolean? = lock.withLock {
    groupBreakpoints[groupId]?.removeIf { it.fileUrl == fileUrl && it.line == line }
  }

  fun isGroupBreakpoint(fileUrl: String, line: Int): Boolean = lock.withLock {
    groupBreakpoints.values.any { set -> set.any { it.fileUrl == fileUrl && it.line == line } }
  }

  fun getBreakpointGroupId(fileUrl: String, line: Int): Int? = lock.withLock {
    groupBreakpoints.entries
      .firstOrNull { (_, set) -> set.any { it.fileUrl == fileUrl && it.line == line } }
      ?.key
  }

  fun removeGroup(groupId: Int): TreeSet<BreakpointDef>? = lock.withLock {
    groupBreakpoints.remove(groupId)
  }

  fun getAllGroupBreakpoints(): Map<Int, List<BreakpointDef>> = lock.withLock {
    groupBreakpoints.mapValues { it.value.toList() }
  }

  fun restore(snapshot: Map<Int, List<BreakpointDef>>): Unit = lock.withLock {
    groupBreakpoints.clear()
    snapshot.forEach { (groupId, defs) ->
      groupBreakpoints[groupId] = TreeSet<BreakpointDef>().also { it.addAll(defs) }
    }
  }
}
