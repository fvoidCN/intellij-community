# Debug Map

断点版本管理插件，为 IntelliJ IDEA 带来 branch 风格的断点切换。

## 核心概念

| 类型 | 定义 | IDE 状态 |
|------|------|----------|
| **active group 断点** | 属于当前激活 group | 可见 |
| **inactive group 断点** | 属于未激活的 group | **真正删除**（非 disable） |

**Service 是唯一真相**：IDE 里的断点只是 service 状态的投影。`checkout` 时，旧 group 的断点从 IDE 删除，新 group 的断点重新加入。

## 工程结构

```
plugins/debug-map/
  src/com/intellij/debugmap/
    DebugMapService.kt               ← 项目级 Service，单一真相源
    model/
      GroupData.kt                   ← GroupData(id, annotation, createdAt)
      BreakpointDef.kt               ← BreakpointDef(fileUrl, line, condition, logExpression, annotation)
      PersistedState.kt              ← XML 序列化 bean，写入 workspace.xml
    manager/
      GroupManager.kt                ← group CRUD + activeGroupId
      BreakpointDefManager.kt        ← groupBreakpoints 增删查
    listener/
      DebugMapBreakpointListener.kt  ← XBreakpointListener，监听用户断点操作同步到 service
    sync/
      BreakpointIdeSyncer.kt         ← checkout 核心：IDE 断点增删 + setDefaultGroup
    mcp/
      DebugMapToolset.kt             ← MCP tools（list/create/delete/checkout）
  resources/META-INF/plugin.xml
```

## 待实现

### 彩色图标

用 `XBreakpointManagerImpl.updateBreakpointPresentation(bp, icon, tooltip)` 在 checkout 时给 active group 的断点染色：
- `group.id % N` 取色盘颜色
- checkout 时批量更新所有 active group 断点的图标

### UI（Tool Window）

在调试工具栏旁增加 Debug Map 面板：
- Group 列表，点击直接 checkout
- 当前 active group 高亮
- 新建 / 删除 / checkout 按钮

## 注意事项

- **不要**创建自定义 `XLineBreakpointType`——`XDebugSessionImpl` 用 `==` 做类型比较，子类会导致调试器无法命中断点
- **不要**把状态存到 `.idea/` 下（会进 git 产生冲突），必须用 `StoragePathMacros.WORKSPACE_FILE`
- checkout / IDE 断点写操作必须在 EDT 上执行（通过 `writeAction {}` 调用）
