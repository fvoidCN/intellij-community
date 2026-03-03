package com.intellij.debugmap

import com.intellij.debugmap.manager.BreakpointDefManager
import com.intellij.debugmap.manager.GroupManager
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.debugmap.model.GroupData
import com.intellij.debugmap.model.PersistedBreakpoint
import com.intellij.debugmap.model.PersistedGroup
import com.intellij.debugmap.model.PersistedState
import com.intellij.debugmap.sync.BreakpointIdeSyncer
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Service(Service.Level.PROJECT)
@State(name = "DebugMap", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DebugMapService(val project: Project) : PersistentStateComponent<PersistedState> {

  private val _groups = MutableStateFlow<List<GroupData>>(emptyList())
  val groups: StateFlow<List<GroupData>> = _groups.asStateFlow()

  private val _activeGroupId = MutableStateFlow<Int?>(null)
  val activeGroupId: StateFlow<Int?> = _activeGroupId.asStateFlow()

  companion object {
    fun getInstance(project: Project): DebugMapService =
      project.getService(DebugMapService::class.java)
  }

  private val groupManager = GroupManager()
  private val breakpointDefManager = BreakpointDefManager()
  private val ideSyncer = BreakpointIdeSyncer(project)

  init {
    // Only initializes in-memory state; does NOT call syncState() so the StateFlow
    // keeps its emptyList() default until loadState() (or noStateLoaded) runs below.
    ensureDefaultGroupQuiet()
  }

  private fun syncState() {
    _groups.value = groupManager.getGroups().map { group ->
      group.copy(breakpoints = breakpointDefManager.getGroupBreakpoints(group.id))
    }
    _activeGroupId.value = groupManager.activeGroupId
  }

  /**
   * Sets activeGroupId directly on the manager without touch or syncState.
   * Called by [BreakpointIdeSyncer] to drive listener behaviour during checkout phases.
   */
  internal fun setActiveGroupId(groupId: Int?) {
    groupManager.activeGroupId = groupId
  }

  override fun getState(): PersistedState = PersistedState().also { state ->
    state.nextGroupId = groupManager.nextGroupId
    state.activeGroupId = groupManager.activeGroupId ?: -1
    state.groups = groupManager.getGroupsSnapshot().values.map { group ->
      PersistedGroup().also { pg ->
        pg.id = group.id
        pg.annotation = group.annotation
        pg.createdAt = group.createdAt
        pg.lastActivatedAt = group.lastActivatedAt
        pg.breakpoints = breakpointDefManager.getGroupBreakpoints(group.id).map { def ->
          PersistedBreakpoint().also { pb ->
            pb.fileUrl = def.fileUrl
            pb.line = def.line
            pb.typeId = def.typeId
            pb.condition = def.condition
            pb.logExpression = def.logExpression
            pb.annotation = def.annotation
          }
        }.toMutableList()
      }
    }.toMutableList()
  }

  /** Called by IntelliJ when there is no previously saved state (new project). */
  override fun noStateLoaded() {
    syncState()
  }

  override fun loadState(state: PersistedState) {
    val groupsSnapshot = state.groups.associate { pg ->
      pg.id to GroupData(
        id = pg.id,
        annotation = pg.annotation,
        createdAt = pg.createdAt,
        lastActivatedAt = pg.lastActivatedAt,
      )
    }
    val activeGroupId = if (state.activeGroupId == -1) null else state.activeGroupId
    groupManager.restore(groupsSnapshot, state.nextGroupId, activeGroupId)

    val breakpointsSnapshot = state.groups.associate { pg ->
      pg.id to pg.breakpoints.map { pb ->
        BreakpointDef(
          fileUrl = pb.fileUrl,
          line = pb.line,
          typeId = pb.typeId,
          condition = pb.condition,
          logExpression = pb.logExpression,
          annotation = pb.annotation,
        )
      }
    }
    breakpointDefManager.restore(breakpointsSnapshot)
    ensureDefaultGroupQuiet()
    syncState()
  }

  val nextGroupId: Int get() = groupManager.nextGroupId

  fun createGroup(annotation: String): Int {
    val id = groupManager.createGroup(annotation.ifBlank { "Group ${groupManager.nextGroupId}" })
    breakpointDefManager.initGroup(id)
    syncState()
    return id
  }

  fun getGroups(): List<GroupData> = _groups.value
  fun groupExists(groupId: Int): Boolean = groupManager.groupExists(groupId)
  fun getActiveGroupId(): Int? = groupManager.activeGroupId

  /**
   * Deletes a group and its breakpoint definitions.
   * The active group cannot be deleted; callers must checkout a different group first.
   * Must be called within a writeAction.
   */
  fun deleteGroup(groupId: Int) {
    check(groupManager.activeGroupId != groupId) { "Cannot delete the active group; checkout another group first" }
    groupManager.deleteGroup(groupId)
    breakpointDefManager.removeGroup(groupId)
    syncState()
  }

  fun getGroupBreakpoints(groupId: Int): List<BreakpointDef> =
    breakpointDefManager.getGroupBreakpoints(groupId)

  fun upsertBreakpointInGroup(groupId: Int, def: BreakpointDef) {
    breakpointDefManager.upsertBreakpointInGroup(groupId, def)
    syncState()
  }

  fun removeBreakpointFromGroup(groupId: Int, fileUrl: String, line: Int) {
    breakpointDefManager.removeBreakpointFromGroup(groupId, fileUrl, line)
    syncState()
  }

  fun isGroupBreakpoint(fileUrl: String, line: Int): Boolean =
    breakpointDefManager.isGroupBreakpoint(fileUrl, line)

  fun getBreakpointGroupId(fileUrl: String, line: Int): Int? =
    breakpointDefManager.getBreakpointGroupId(fileUrl, line)

  /** Must be called within a writeAction. Switches active group and syncs IDE breakpoints. */
  fun checkout(targetGroupId: Int?) {
    ideSyncer.checkout(targetGroupId)
    if (targetGroupId != null) groupManager.touchGroup(targetGroupId)
    syncState()
  }

  /**
   * Ensures there is always at least one group and an active group.
   * Does NOT call syncState() — callers are responsible for that.
   */
  private fun ensureDefaultGroupQuiet() {
    if (groupManager.getGroups().isEmpty()) {
      val id = groupManager.createGroup("Default")
      breakpointDefManager.initGroup(id)
      groupManager.activeGroupId = id
    }
    else if (groupManager.activeGroupId == null) {
      groupManager.activeGroupId = groupManager.getGroups().first().id
    }
  }
}
