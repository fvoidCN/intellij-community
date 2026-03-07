package com.intellij.debugmap

import com.intellij.debugmap.manager.BreakpointManager
import com.intellij.debugmap.model.BookmarkDef
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.debugmap.model.GroupData
import com.intellij.debugmap.model.PersistedBookmark
import com.intellij.debugmap.model.PersistedBreakpoint
import com.intellij.debugmap.model.PersistedGroup
import com.intellij.debugmap.model.PersistedState
import com.intellij.ide.bookmark.BookmarkType
import com.intellij.debugmap.manager.BreakpointIdeManager
import com.intellij.debugmap.sync.BreakpointMarkerTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter

@Service(Service.Level.PROJECT)
@State(name = "DebugMap", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DebugMapService(val project: Project) : PersistentStateComponent<PersistedState>, Disposable {

  private val _groups = MutableStateFlow<List<GroupData>>(emptyList())
  val groups: StateFlow<List<GroupData>> = _groups.asStateFlow()

  private val _activeGroupId = MutableStateFlow<Int?>(null)
  val activeGroupId: StateFlow<Int?> = _activeGroupId.asStateFlow()

  companion object {
    fun getInstance(project: Project): DebugMapService =
      project.getService(DebugMapService::class.java)
  }

  private val breakpointManager = BreakpointManager()
  private val ideManager = BreakpointIdeManager(project)
  private val markerTracker = BreakpointMarkerTracker(this)

  init {
    // Only initializes in-memory state; does NOT call syncState() so the StateFlow
    // keeps its emptyList() default until loadState() (or noStateLoaded) runs below.
    ensureDefaultGroup()
  }

  override fun dispose() {
  }

  private fun syncState() {
    _groups.value = breakpointManager.getGroups()
    _activeGroupId.value = breakpointManager.activeGroupId
  }

  /**
   * Sets the active group on the in-memory manager and updates both IDE default groups
   * (breakpoint and bookmark) so that add/remove events during checkout phases are
   * routed to the right group.
   */
  internal fun setActiveGroupId(groupId: Int?) {
    breakpointManager.activeGroupId = groupId
    val groupName = groupId?.let { breakpointManager.getGroup(it)?.name }
    ideManager.setDefaultGroup(groupName)
  }

  override fun getState(): PersistedState = PersistedState().also { state ->
    state.nextGroupId = breakpointManager.nextGroupId
    state.activeGroupId = breakpointManager.activeGroupId ?: -1
    state.groups = breakpointManager.getGroupsSnapshot().values.map { group ->
      PersistedGroup().also { pg ->
        pg.id = group.id
        pg.name = group.name
        pg.breakpoints = group.breakpoints.map { def ->
          PersistedBreakpoint().also { pb ->
            pb.fileUrl = def.fileUrl
            pb.line = markerTracker.getCurrentLine(group.id, def)
            pb.typeId = def.typeId
            pb.condition = def.condition
            pb.logExpression = def.logExpression
            pb.name = def.name?.ifEmpty { null }
          }
        }.toMutableList()
        pg.bookmarks = group.bookmarks.map { def ->
          PersistedBookmark().also { pb ->
            pb.fileUrl = def.fileUrl
            pb.line = def.line
            pb.name = def.name?.ifEmpty { null }
            pb.bookmarkType = def.type.name
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
        name = pg.name,
        breakpoints = pg.breakpoints.map { pb ->
          BreakpointDef(
            groupId = pg.id,
            fileUrl = pb.fileUrl,
            line = pb.line,
            typeId = pb.typeId,
            condition = pb.condition,
            logExpression = pb.logExpression,
            name = pb.name,
          )
        },
        bookmarks = pg.bookmarks.map { pb ->
          BookmarkDef(
            groupId = pg.id,
            fileUrl = pb.fileUrl,
            line = pb.line,
            name = pb.name,
            type = runCatching { BookmarkType.valueOf(pb.bookmarkType) }.getOrDefault(BookmarkType.DEFAULT),
          )
        },
      )
    }
    val activeGroupId = if (state.activeGroupId == -1) null else state.activeGroupId
    breakpointManager.restore(groupsSnapshot, state.nextGroupId, activeGroupId)
    ensureDefaultGroup()
    syncState()
  }

  val nextGroupId: Int get() = breakpointManager.nextGroupId

  fun createGroup(name: String): Int {
    val id = breakpointManager.createGroup(name.ifBlank { "Group ${breakpointManager.nextGroupId}" })
    syncState()
    return id
  }

  fun renameGroup(groupId: Int, name: String) {
    breakpointManager.renameGroup(groupId, name)
    syncState()
  }

  fun renameBreakpoint(def: BreakpointDef, name: String) {
    breakpointManager.upsertBreakpointInGroup(def.groupId, def.copy(name = name.trim()))
    syncState()
  }

  fun renameBookmark(def: BookmarkDef, name: String) {
    breakpointManager.upsertBookmarkInGroup(def.groupId, def.copy(name = name.trim()))
    syncState()
  }

  fun getGroups(): List<GroupData> = _groups.value
  fun groupExists(groupId: Int): Boolean = breakpointManager.groupExists(groupId)
  fun getActiveGroupId(): Int? = breakpointManager.activeGroupId

  /**
   * Deletes a group and its breakpoint definitions.
   * The active group cannot be deleted; callers must checkout a different group first.
   * Must be called within a writeAction.
   */
  fun deleteGroup(groupId: Int) {
    check(breakpointManager.activeGroupId != groupId) { "Cannot delete the active group; checkout another group first" }
    breakpointManager.deleteGroup(groupId)
    syncState()
  }

  fun getGroupBreakpoints(groupId: Int): List<BreakpointDef> =
    breakpointManager.getGroupBreakpoints(groupId)

  fun getGroupBookmarks(groupId: Int): List<BookmarkDef> =
    breakpointManager.getGroupBookmarks(groupId)

  fun getBreakpointsByFile(fileUrl: String): List<BreakpointDef> =
    breakpointManager.getBreakpointsByFile(fileUrl)

  fun upsertBreakpointInGroup(groupId: Int, def: BreakpointDef) {
    breakpointManager.upsertBreakpointInGroup(groupId, def)
    syncState()
  }

  fun removeBreakpointFromGroup(groupId: Int, fileUrl: String, line: Int) {
    breakpointManager.removeBreakpointFromGroup(groupId, fileUrl, line)
    syncState()
  }

  fun moveBreakpointLine(def: BreakpointDef, newLine: Int) {
    breakpointManager.moveBreakpointLine(def, newLine)
    syncState()
  }

  fun isGroupBreakpoint(fileUrl: String, line: Int): Boolean =
    breakpointManager.isGroupBreakpoint(fileUrl, line)

  fun getBreakpointGroupId(fileUrl: String, line: Int): Int? =
    breakpointManager.getBreakpointGroupId(fileUrl, line)

  fun getBookmarksByFile(fileUrl: String): List<BookmarkDef> =
    breakpointManager.getBookmarksByFile(fileUrl)

  fun getBookmarkGroupId(fileUrl: String, line: Int): Int? =
    breakpointManager.getBookmarkGroupId(fileUrl, line)

  fun upsertBookmarkInGroup(groupId: Int, def: BookmarkDef) {
    breakpointManager.upsertBookmarkInGroup(groupId, def)
    syncState()
  }

  fun removeBookmarkFromGroup(groupId: Int, fileUrl: String, line: Int) {
    breakpointManager.removeBookmarkFromGroup(groupId, fileUrl, line)
    syncState()
  }

  fun moveBookmarkLine(def: BookmarkDef, newLine: Int) {
    breakpointManager.moveBookmarkLine(def, newLine)
    syncState()
  }

  /** Must be called within a writeAction. Switches active group and syncs IDE breakpoints. */
  fun checkout(targetGroupId: Int?) {
    markerTracker.flushAll()
    ideManager.checkout(targetGroupId, this)
    syncState()
    markerTracker.initForOpenFiles()
  }

  internal fun onFileOpened(file: VirtualFile) = markerTracker.onFileOpened(file)
  internal fun onFileClosed(file: VirtualFile) = markerTracker.onFileClosed(file)
  internal fun getCurrentLine(groupId: Int, def: BreakpointDef) = markerTracker.getCurrentLine(groupId, def)
  internal fun dropFileEntries(fileUrl: String) = markerTracker.dropFileEntries(fileUrl)

  /**
   * Ensures there is always at least one group and an active group.
   */
  private fun ensureDefaultGroup() {
    if (breakpointManager.getGroups().isEmpty()) {
      val id = breakpointManager.createGroup("Default")
      breakpointManager.activeGroupId = id
    }
    else if (breakpointManager.activeGroupId == null) {
      breakpointManager.activeGroupId = breakpointManager.getGroups().first().id
    }
  }

  /**
   * Imports IDE line breakpoints that are not yet assigned to any group into the active group.
   * Must be called after the project is fully opened so that [XBreakpointManagerImpl] has
   * loaded its state from disk. Intended to be called from [DebugMapStartupActivity].
   */
  internal fun importFloatingBreakpoints() {
    val targetGroupId = breakpointManager.activeGroupId ?: return
    ideManager.allLineBreakpoints()
      .filter { !breakpointManager.isGroupBreakpoint(it.fileUrl, it.line) }
      .forEach { bp ->
        breakpointManager.upsertBreakpointInGroup(
          targetGroupId,
          BreakpointDef(
            groupId = targetGroupId,
            fileUrl = bp.fileUrl,
            line = bp.line,
            typeId = bp.type.id,
            condition = bp.conditionExpression?.expression,
            logExpression = bp.logExpressionObject?.expression,
          )
        )
      }
    syncState()
  }
}
