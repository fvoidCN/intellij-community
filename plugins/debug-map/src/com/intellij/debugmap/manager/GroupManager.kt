package com.intellij.debugmap.manager

import com.intellij.debugmap.model.GroupData
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class GroupManager {

  private val lock = ReentrantLock()
  private val groups = mutableMapOf<Int, GroupData>()
  private var _nextGroupId: Int = 1
  private var _activeGroupId: Int? = null

  val nextGroupId: Int get() = lock.withLock { _nextGroupId }
  var activeGroupId: Int?
    get() = lock.withLock { _activeGroupId }
    set(value) {
      lock.withLock { _activeGroupId = value }
    }

  fun createGroup(annotation: String): Int = lock.withLock {
    val id = _nextGroupId++
    groups[id] = GroupData(id = id, annotation = annotation, createdAt = System.currentTimeMillis())
    id
  }

  fun touchGroup(id: Int): Unit = lock.withLock {
    val group = groups[id] ?: return@withLock
    groups[id] = group.copy(lastActivatedAt = System.currentTimeMillis())
  }

  fun getGroups(): List<GroupData> = lock.withLock {
    groups.values.sortedByDescending { it.lastActivatedAt }
  }

  fun groupExists(groupId: Int): Boolean = lock.withLock { groups.containsKey(groupId) }

  fun deleteGroup(groupId: Int) = lock.withLock { groups.remove(groupId) }

  fun getGroupsSnapshot(): Map<Int, GroupData> = lock.withLock { groups.toMap() }

  fun restore(snapshot: Map<Int, GroupData>, nextGroupId: Int, activeGroupId: Int?) = lock.withLock {
    groups.clear()
    groups.putAll(snapshot)
    _nextGroupId = nextGroupId
    _activeGroupId = activeGroupId
  }
}
