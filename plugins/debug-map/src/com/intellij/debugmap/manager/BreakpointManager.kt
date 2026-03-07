package com.intellij.debugmap.manager

import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.debugmap.model.GroupData
import com.intellij.debugmap.model.LocationDef
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BreakpointManager {

  private val lock = ReentrantLock()
  private val groups = mutableMapOf<Int, GroupData>()
  private var _nextGroupId: Int = 1
  private var _activeGroupId: Int? = null

  /** Secondary index: fileUrl → all LocationDefs across all groups for fast file-based lookups. */
  private val fileMap = mutableMapOf<String, MutableList<LocationDef>>()

  val nextGroupId: Int get() = lock.withLock { _nextGroupId }
  var activeGroupId: Int?
    get() = lock.withLock { _activeGroupId }
    set(value) {
      lock.withLock { _activeGroupId = value }
    }

  // region Groups

  fun createGroup(name: String): Int = lock.withLock {
    val id = _nextGroupId++
    groups[id] = GroupData(id = id, name = name)
    id
  }

  fun renameGroup(id: Int, name: String): Unit = lock.withLock {
    val group = groups[id] ?: return@withLock
    groups[id] = group.copy(name = name)
  }

  fun getGroup(id: Int): GroupData? = lock.withLock {
    groups[id]
  }

  fun getGroups(): List<GroupData> = lock.withLock {
    groups.values.toList()
  }

  fun groupExists(groupId: Int): Boolean = lock.withLock { groups.containsKey(groupId) }

  fun deleteGroup(groupId: Int): Unit = lock.withLock {
    val group = groups.remove(groupId) ?: return@withLock
    group.breakpoints.forEach { def -> fileMap[def.fileUrl]?.remove(def) }
    group.bookmarks.forEach { def -> fileMap[def.fileUrl]?.remove(def) }
    fileMap.entries.removeIf { (_, list) -> list.isEmpty() }
  }

  fun getGroupsSnapshot(): Map<Int, GroupData> = lock.withLock { groups.toMap() }

  fun restore(snapshot: Map<Int, GroupData>, nextGroupId: Int, activeGroupId: Int?): Unit = lock.withLock {
    groups.clear()
    fileMap.clear()
    groups.putAll(snapshot)
    _nextGroupId = nextGroupId
    _activeGroupId = activeGroupId
    snapshot.values.forEach { group ->
      group.breakpoints.forEach { def -> fileMap.getOrPut(def.fileUrl) { mutableListOf() }.add(def) }
      group.bookmarks.forEach { def -> fileMap.getOrPut(def.fileUrl) { mutableListOf() }.add(def) }
    }
  }

  // endregion

  // region Breakpoints

  fun getGroupBreakpoints(groupId: Int): List<BreakpointDef> = lock.withLock {
    groups[groupId]?.breakpoints ?: emptyList()
  }

  fun upsertBreakpointInGroup(groupId: Int, def: BreakpointDef): Unit =
    upsertInGroup(groupId, def, { it.breakpoints }, { g, l -> g.copy(breakpoints = l) }) { copy(name = it) }

  fun removeBreakpointFromGroup(groupId: Int, fileUrl: String, line: Int): Unit = lock.withLock {
    val group = groups[groupId] ?: return@withLock
    groups[groupId] = group.copy(breakpoints = group.breakpoints.filter { !(it.fileUrl == fileUrl && it.line == line) })
    fileMap[fileUrl]?.removeIf { it.groupId == groupId && it.line == line && it is BreakpointDef }
  }

  /**
   * Moves [def] to [newLine] within its group atomically, preserving [name].
   */
  fun moveBreakpointLine(def: BreakpointDef, newLine: Int): Unit = lock.withLock {
    val group = groups[def.groupId] ?: return@withLock
    val list = group.breakpoints.toMutableList()
    val idx = list.indexOfFirst { it.fileUrl == def.fileUrl && it.line == def.line }
    if (idx < 0) return@withLock
    val movedDef = def.copy(line = newLine)
    list[idx] = movedDef
    groups[def.groupId] = group.copy(breakpoints = list)
    fileMap[def.fileUrl]?.apply {
      val fIdx = indexOfFirst { it.groupId == def.groupId && it.line == def.line && it is BreakpointDef }
      if (fIdx >= 0) set(fIdx, movedDef)
    }
  }

  fun getBreakpointsByFile(fileUrl: String): List<BreakpointDef> = lock.withLock {
    fileMap[fileUrl]?.filterIsInstance<BreakpointDef>() ?: emptyList()
  }

  fun isGroupBreakpoint(fileUrl: String, line: Int): Boolean = lock.withLock {
    fileMap[fileUrl]?.any { it is BreakpointDef && it.line == line } ?: false
  }

  fun getBreakpointGroupId(fileUrl: String, line: Int): Int? = lock.withLock {
    fileMap[fileUrl]?.firstOrNull { it is BreakpointDef && it.line == line }?.groupId
  }

  fun getGroupBookmarks(groupId: Int): List<BookmarkDef> = lock.withLock {
    groups[groupId]?.bookmarks ?: emptyList()
  }

  /**
   * Adds or replaces a bookmark in [groupId].
   * Uniqueness key is (fileUrl, line). If [def.name] is null, the existing entry's name is preserved.
   */
  fun upsertBookmarkInGroup(groupId: Int, def: BookmarkDef): Unit =
    upsertInGroup(groupId, def, { it.bookmarks }, { g, l -> g.copy(bookmarks = l) }) { copy(name = it) }

  private inline fun <reified T : LocationDef> upsertInGroup(
    groupId: Int,
    def: T,
    getList: (GroupData) -> List<T>,
    updateGroup: (GroupData, List<T>) -> GroupData,
    withName: T.(String?) -> T,
  ): Unit = lock.withLock {
    val group = groups[groupId] ?: return@withLock
    val list = getList(group).toMutableList()
    val idx = list.indexOfFirst { it.fileUrl == def.fileUrl && it.line == def.line }
    val existing = list.getOrNull(idx)
    val storedDef = if (existing != null && def.name == null) def.withName(existing.name) else def
    if (idx >= 0) list[idx] = storedDef else list.add(storedDef)
    groups[groupId] = updateGroup(group, list)
    fileMap.getOrPut(storedDef.fileUrl) { mutableListOf() }.apply {
      val fIdx = indexOfFirst { it.groupId == groupId && it.line == storedDef.line && it is T }
      if (fIdx >= 0) set(fIdx, storedDef) else add(storedDef)
    }
  }

  fun removeBookmarkFromGroup(groupId: Int, fileUrl: String, line: Int): Unit = lock.withLock {
    val group = groups[groupId] ?: return@withLock
    groups[groupId] = group.copy(bookmarks = group.bookmarks.filter { !(it.fileUrl == fileUrl && it.line == line) })
    fileMap[fileUrl]?.removeIf { it.groupId == groupId && it.line == line && it is BookmarkDef }
  }

  fun getBookmarksByFile(fileUrl: String): List<BookmarkDef> = lock.withLock {
    fileMap[fileUrl]?.filterIsInstance<BookmarkDef>() ?: emptyList()
  }

  fun getBookmarkGroupId(fileUrl: String, line: Int): Int? = lock.withLock {
    fileMap[fileUrl]?.firstOrNull { it is BookmarkDef && it.line == line }?.groupId
  }

  fun moveBookmarkLine(def: BookmarkDef, newLine: Int): Unit = lock.withLock {
    val group = groups[def.groupId] ?: return@withLock
    val list = group.bookmarks.toMutableList()
    val idx = list.indexOfFirst { it.fileUrl == def.fileUrl && it.line == def.line }
    if (idx < 0) return@withLock
    val movedDef = def.copy(line = newLine)
    list[idx] = movedDef
    groups[def.groupId] = group.copy(bookmarks = list)
    fileMap[def.fileUrl]?.apply {
      val fIdx = indexOfFirst { it.groupId == def.groupId && it.line == def.line && it is BookmarkDef }
      if (fIdx >= 0) set(fIdx, movedDef)
    }
  }

  fun getLocationsByFile(fileUrl: String): List<LocationDef> = lock.withLock {
    fileMap[fileUrl]?.toList() ?: emptyList()
  }
}
