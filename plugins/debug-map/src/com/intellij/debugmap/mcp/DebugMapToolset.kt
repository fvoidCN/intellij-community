@file:Suppress("FunctionName", "unused")

package com.intellij.debugmap.mcp

import com.intellij.debugmap.DebugMapService
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.reportToolActivity
import com.intellij.openapi.application.writeAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

// TODO: 当前 MCP tools 的粒度太细，不适合 AI 直接使用：步骤多、上下文依赖强、容易出错。
//  后续需要重新设计更高层次的 tools，例如：
//    - 把"创建 group 并切换到它"合并为一个原子操作
//    - 提供面向任务的 API（"为这个 debug 任务保存断点现场"）
//    - 减少 AI 需要记住的中间状态（如 activeGroupId）
//  目前作为功能验证和手动测试工具使用。

class DebugMapToolset : McpToolset {

  // ──────────────────────────────────────────────────────────────────────────
  // Group management
  // ──────────────────────────────────────────────────────────────────────────

  @McpTool(name = "debugmap_list_groups")
  @McpDescription("""
        |Lists all breakpoint groups and the current active group.
        |Each group stores a named set of breakpoints. Only the active group's breakpoints
        |are visible in the IDE; all others are hidden until you checkout that group.
    """)
  suspend fun list_groups(): GroupListResult {
    currentCoroutineContext().reportToolActivity("Listing debug map groups")
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    val groups = service.getGroups().map { g ->
      GroupInfo(
        id = g.id,
        annotation = g.annotation,
        breakpointCount = service.getGroupBreakpoints(g.id).size,
        active = g.id == service.getActiveGroupId(),
      )
    }
    return GroupListResult(groups = groups, activeGroupId = service.getActiveGroupId())
  }

  @McpTool(name = "debugmap_create_group")
  @McpDescription("""
        |Creates a new breakpoint group and immediately switches to it.
        |The previous group's breakpoints are hidden; new breakpoints will belong to this group.
    """)
  suspend fun create_group(
    @McpDescription("A short name or label for this group, e.g. 'auth-flow' or 'perf-hotspots'")
    annotation: String,
  ): GroupResult {
    currentCoroutineContext().reportToolActivity("Creating debug map group '$annotation'")
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    val previousId = service.getActiveGroupId()
    val id = service.createGroup(annotation)
    writeAction { service.checkout(id) }
    return GroupResult(id = id, annotation = annotation, previousGroupId = previousId, status = "created")
  }

  @McpTool(name = "debugmap_delete_group")
  @McpDescription("""
        |Deletes a breakpoint group and all its breakpoint definitions.
        |If the group is currently active, its breakpoints are removed from the IDE first.
        |Use debugmap_checkout to switch to another group afterwards.
    """)
  suspend fun delete_group(
    @McpDescription("ID of the group to delete")
    groupId: Int,
  ): GroupResult {
    currentCoroutineContext().reportToolActivity("Deleting debug map group $groupId")
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    if (!service.groupExists(groupId)) {
      mcpFail("Group $groupId does not exist. Use debugmap_list_groups to see available groups.")
    }
    val annotation = service.getGroups().first { it.id == groupId }.annotation
    writeAction { service.deleteGroup(groupId) }

    return GroupResult(id = groupId, annotation = annotation, status = "deleted")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Checkout
  // ──────────────────────────────────────────────────────────────────────────

  @McpTool(name = "debugmap_checkout")
  @McpDescription("""
        |Switches the active breakpoint group.
        |The current active group's breakpoints are removed from the IDE.
        |The target group's breakpoints are added to the IDE.
        |New breakpoints set by the user will automatically belong to the active group.
    """)
  suspend fun checkout(
    @McpDescription("ID of the group to activate")
    groupId: Int,
  ): CheckoutResult {
    currentCoroutineContext().reportToolActivity("Checking out debug map group $groupId")
    val project = currentCoroutineContext().project
    val service = DebugMapService.getInstance(project)

    if (!service.groupExists(groupId)) {
      mcpFail("Group $groupId does not exist. Use debugmap_list_groups to see available groups.")
    }

    val previousId = service.getActiveGroupId()
    writeAction { service.checkout(groupId) }

    return CheckoutResult(previousGroupId = previousId, activeGroupId = groupId, status = "ok")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Result types
  // ──────────────────────────────────────────────────────────────────────────

  @Serializable
  data class GroupInfo(
    val id: Int,
    val annotation: String,
    val breakpointCount: Int,
    val active: Boolean,
  )

  @Serializable
  data class GroupListResult(
    val groups: List<GroupInfo>,
    val activeGroupId: Int?,
  )

  @Serializable
  data class GroupResult(
    val id: Int,
    val annotation: String,
    val previousGroupId: Int? = null,
    val status: String,
  )

  @Serializable
  data class CheckoutResult(
    val previousGroupId: Int?,
    val activeGroupId: Int,
    val status: String,
  )
}
