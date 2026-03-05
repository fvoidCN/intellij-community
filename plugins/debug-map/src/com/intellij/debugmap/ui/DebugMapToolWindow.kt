package com.intellij.debugmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.BottomEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.debugmap.DebugMapService
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.rememberTreeState
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun DebugMapToolWindow(project: Project) {
  val service = remember(project) { DebugMapService.getInstance(project) }
  val groups by service.groups.collectAsState()
  val activeGroupId by service.activeGroupId.collectAsState()
  var selectedGroupId by remember { mutableStateOf<Int?>(null) }
  val treeState = rememberTreeState()

  val tree = remember(groups, activeGroupId) {
    buildTree<DebugMapNode> {
      for (group in groups) {
        addNode(
          data = DebugMapNode.Group(group.id, group.annotation, group.id == activeGroupId),
          id = "group-${group.id}",
        ) {
          for (bp in group.breakpoints) {
            addLeaf(
              data = DebugMapNode.BreakpointItem(group.id, bp),
              id = "bp-${group.id}-${bp.fileUrl}-${bp.line}",
            )
          }
        }
      }
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      IconActionButton(
        key = AllIconsKeys.General.Add,
        contentDescription = "New Group",
        onClick = {
          val defaultName = "Group ${service.nextGroupId}"
          val name = Messages.showInputDialog(project, "Group name:", "New Debug Group", null, defaultName, null)
                     ?: return@IconActionButton
          WriteAction.run<Exception> { service.createGroup(name) }
        },
      )
      IconActionButton(
        key = AllIconsKeys.General.Remove,
        contentDescription = "Delete Group",
        enabled = selectedGroupId != null && selectedGroupId != activeGroupId,
        onClick = {
          val gId = selectedGroupId ?: return@IconActionButton
          WriteAction.run<Exception> { service.deleteGroup(gId) }
          selectedGroupId = null
        },
      )
      IconActionButton(
        key = AllIconsKeys.Actions.CheckOut,
        contentDescription = "Checkout Group",
        enabled = selectedGroupId != null && selectedGroupId != activeGroupId,
        onClick = {
          val gId = selectedGroupId ?: return@IconActionButton
          WriteAction.run<Exception> { service.checkout(gId) }
        },
      )
    }

    Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())

    LazyTree(
      tree = tree,
      modifier = Modifier.fillMaxSize(),
      treeState = treeState,
      onSelectionChange = { elements ->
        selectedGroupId = elements.firstOrNull()?.let { elem ->
          when (val node = elem.data) {
            is DebugMapNode.Group -> node.id
            is DebugMapNode.BreakpointItem -> node.groupId
          }
        }
      },
      onElementDoubleClick = { element ->
        val node = element.data
        if (node is DebugMapNode.BreakpointItem) {
          val file = VirtualFileManager.getInstance().findFileByUrl(node.def.fileUrl)
          if (file != null) {
            OpenFileDescriptor(project, file, node.def.line, 0).navigate(true)
          }
        }
      },
    ) { element ->
      when (val node = element.data) {
        is DebugMapNode.Group -> GroupRow(node)
        is DebugMapNode.BreakpointItem -> BreakpointRow(node)
      }
    }
  }
}

@Composable
private fun GroupRow(node: DebugMapNode.Group) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = node.name,
      fontWeight = if (node.isActive) FontWeight.Bold else FontWeight.Normal,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (node.isActive) {
      Text(text = "●")
    }
  }
}

@Composable
private fun BreakpointRow(node: DebugMapNode.BreakpointItem) {
  val def = node.def
  val baseIconKey = when {
    !def.logExpression.isNullOrBlank() -> AllIconsKeys.Debugger.Db_no_suspend_breakpoint
    def.typeId == "java-method" -> AllIconsKeys.Debugger.Db_method_breakpoint
    def.typeId == "java-field" -> AllIconsKeys.Debugger.Db_field_breakpoint
    else -> AllIconsKeys.Debugger.Db_set_breakpoint
  }
  val hasCondition = !def.condition.isNullOrBlank()
  val fileName = def.fileUrl.substringAfterLast('/')
  val lineNumber = def.line + 1
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box(modifier = Modifier.size(12.dp)) {
      Icon(key = baseIconKey, contentDescription = null, modifier = Modifier.size(12.dp))
      if (hasCondition) {
        Icon(
          key = AllIconsKeys.Debugger.Question_badge,
          contentDescription = null,
          modifier = Modifier.size(7.dp).align(BottomEnd),
        )
      }
    }
    Text(
      text = "$fileName:$lineNumber",
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
  }
}
