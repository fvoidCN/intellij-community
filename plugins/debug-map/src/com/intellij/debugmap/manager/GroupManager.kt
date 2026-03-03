package com.intellij.debugmap.manager

import com.intellij.debugmap.model.GroupData

class GroupManager {

  private val groups = mutableMapOf<Int, GroupData>()
  var nextGroupId: Int = 1
    private set
  var activeGroupId: Int? = null

  fun createGroup(annotation: String): Int {
    val id = nextGroupId++
    groups[id] = GroupData(id = id, annotation = annotation, createdAt = System.currentTimeMillis())
    return id
  }

  fun touchGroup(id: Int) {
    val group = groups[id] ?: return
    groups[id] = group.copy(lastActivatedAt = System.currentTimeMillis())
  }

  fun getGroups(): List<GroupData> = groups.values.sortedByDescending { it.lastActivatedAt }

  fun groupExists(groupId: Int): Boolean = groups.containsKey(groupId)

  fun deleteGroup(groupId: Int) {
    groups.remove(groupId)
  }

  fun getGroupsSnapshot(): Map<Int, GroupData> = groups.toMap()

  fun restore(snapshot: Map<Int, GroupData>, nextGroupId: Int, activeGroupId: Int?) {
    groups.clear()
    groups.putAll(snapshot)
    this.nextGroupId = nextGroupId
    this.activeGroupId = activeGroupId
  }
}
