package com.intellij.debugmap

import com.intellij.debugmap.manager.BreakpointDefManager
import com.intellij.debugmap.manager.GroupManager
import com.intellij.debugmap.sync.BreakpointIdeSyncer
import com.intellij.debugmap.model.BreakpointDef
import com.intellij.debugmap.model.GroupData
import com.intellij.debugmap.model.PersistedBreakpoint
import com.intellij.debugmap.model.PersistedGroup
import com.intellij.debugmap.model.PersistedState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "DebugMap", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DebugMapService(project: Project) : PersistentStateComponent<PersistedState> {

    private val groupManager = GroupManager()
    private val breakpointDefManager = BreakpointDefManager()
    private val ideSyncer = BreakpointIdeSyncer(project)

    // TODO: isSyncing 是一个粗粒度的重入抑制 flag，用于阻止 DebugMapBreakpointListener
    //  在我们自己触发的断点变更事件中产生反馈环。
    //  更好的方案是精确的重入检测机制（如 ThreadLocal token 或事件来源标记），
    //  但 IntelliJ 框架目前不提供这个能力，暂时维持现状。
    //  同时，service 内部写操作依赖调用方保证在 writeAction 中执行，
    //  这个约定目前没有在 API 层面强制（可考虑加 assertWriteAccessAllowed() 断言）。
    @Volatile
    var isSyncing: Boolean = false

    init {
        ensureDefaultGroup()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Persistence
    // ──────────────────────────────────────────────────────────────────────────

    override fun getState(): PersistedState = PersistedState().also { state ->
        state.nextGroupId = groupManager.nextGroupId
        state.activeGroupId = groupManager.activeGroupId ?: -1
        state.groups = groupManager.getGroupsSnapshot().values.map { group ->
            PersistedGroup().also { pg ->
                pg.id = group.id
                pg.annotation = group.annotation
                pg.createdAt = group.createdAt
                pg.breakpoints = breakpointDefManager.getGroupBreakpoints(group.id).map { def ->
                    PersistedBreakpoint().also { pb ->
                        pb.fileUrl = def.fileUrl
                        pb.line = def.line
                        pb.condition = def.condition
                        pb.logExpression = def.logExpression
                        pb.annotation = def.annotation
                    }
                }.toMutableList()
            }
        }.toMutableList()
    }

    override fun loadState(state: PersistedState) {
        val groupsSnapshot = state.groups.associate { pg ->
            pg.id to GroupData(id = pg.id, annotation = pg.annotation, createdAt = pg.createdAt)
        }
        val activeGroupId = if (state.activeGroupId == -1) null else state.activeGroupId
        groupManager.restore(groupsSnapshot, state.nextGroupId, activeGroupId)

        val breakpointsSnapshot = state.groups.associate { pg ->
            pg.id to pg.breakpoints.map { pb ->
                BreakpointDef(
                    fileUrl = pb.fileUrl,
                    line = pb.line,
                    condition = pb.condition,
                    logExpression = pb.logExpression,
                    annotation = pb.annotation,
                )
            }
        }
        breakpointDefManager.restore(breakpointsSnapshot)
        ensureDefaultGroup()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Group API
    // ──────────────────────────────────────────────────────────────────────────

    fun createGroup(annotation: String): Int {
        val id = groupManager.createGroup(annotation)
        breakpointDefManager.initGroup(id)
        return id
    }

    fun getGroups(): List<GroupData> = groupManager.getGroups()
    fun groupExists(groupId: Int): Boolean = groupManager.groupExists(groupId)
    fun getActiveGroupId(): Int? = groupManager.activeGroupId
    fun setActiveGroupId(groupId: Int?) { groupManager.activeGroupId = groupId }

    /**
     * Deletes a group and its breakpoint definitions.
     * If the group is currently active, its breakpoints are removed from the IDE first (checkout to null).
     * Must be called within a writeAction.
     */
    fun deleteGroup(groupId: Int) {
        if (groupManager.activeGroupId == groupId) {
            ideSyncer.checkout(null)
        }
        groupManager.deleteGroup(groupId)
        breakpointDefManager.removeGroup(groupId)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Breakpoint definition API
    // ──────────────────────────────────────────────────────────────────────────

    fun getGroupBreakpoints(groupId: Int): List<BreakpointDef> =
        breakpointDefManager.getGroupBreakpoints(groupId)

    fun addBreakpointToGroup(groupId: Int, def: BreakpointDef) =
        breakpointDefManager.addBreakpointToGroup(groupId, def)

    fun removeBreakpointFromGroup(groupId: Int, fileUrl: String, line: Int) =
        breakpointDefManager.removeBreakpointFromGroup(groupId, fileUrl, line)

    fun isGroupBreakpoint(fileUrl: String, line: Int): Boolean =
        breakpointDefManager.isGroupBreakpoint(fileUrl, line)

    fun getBreakpointGroupId(fileUrl: String, line: Int): Int? =
        breakpointDefManager.getBreakpointGroupId(fileUrl, line)

    // ──────────────────────────────────────────────────────────────────────────
    // Checkout API
    // ──────────────────────────────────────────────────────────────────────────

    /** Must be called within a writeAction. Switches active group and syncs IDE breakpoints. */
    fun checkout(targetGroupId: Int?) = ideSyncer.checkout(targetGroupId)

    // ──────────────────────────────────────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────────────────────────────────────

    /** Ensures there is always at least one group and an active group. */
    private fun ensureDefaultGroup() {
        if (groupManager.getGroups().isEmpty()) {
            val id = groupManager.createGroup("Default")
            breakpointDefManager.initGroup(id)
            groupManager.activeGroupId = id
        } else if (groupManager.activeGroupId == null) {
            groupManager.activeGroupId = groupManager.getGroups().first().id
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Companion
    // ──────────────────────────────────────────────────────────────────────────

    companion object {
        fun getInstance(project: Project): DebugMapService =
            project.getService(DebugMapService::class.java)
    }
}
