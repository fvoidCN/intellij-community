# Debug Map

断点版本管理插件，为 IntelliJ IDEA 带来 git 风格的断点工作流。

## 核心概念

### 断点分类

| 类型 | 定义 | 状态 |
|------|------|------|
| **no-group 断点** | 不属于任何 group，正常存在于 IDE | 始终可见 |
| **group 断点（active group）** | 属于当前激活 group | IDE 中可见 |
| **group 断点（inactive group）** | 属于未激活的 group | **完全不存在于 IDE**（非 disable，是真正隐藏） |

**Service 是唯一真相**：IDE 里的断点只是 service 状态的投影。

### Git 类比

```
no-group 断点   ≈  working directory（未暂存的修改）
group           ≈  branch
checkout        ≈  git checkout <branch>
commit          ≈  git commit（把 no-group 断点存入 group）
stash / pop     ≈  git stash / git stash pop
```

### 数据模型

```kotlin
GroupData(id: Int, annotation: String, createdAt: Long)

BreakpointDef(fileUrl: String, line: Int,  // line 是 0-based
              typeId: String,
              condition: String?, logExpression: String?, annotation: String?)

StashEntry(activeGroupId: Int?, noGroupBreakpoints: List<BreakpointSnapshot>)
```

---

## 工程结构

```
plugins/debug-map/
  src/com/intellij/debugmap/
    DebugMapService.kt          ← 项目级 Service，单一真相源，公开 API 入口
    model/
      GroupData.kt              ← GroupData 数据类
      BreakpointDef.kt          ← BreakpointDef / BreakpointSnapshot / StashEntry
      PersistedState.kt         ← XML 序列化 bean（PersistedBreakpoint/Group/State）
    manager/
      GroupManager.kt           ← group CRUD + activeGroupId + nextGroupId
      BreakpointDefManager.kt   ← groupBreakpoints 增删查
      StashManager.kt           ← stash 栈（内存，重启清空）
    listener/
      DebugMapBreakpointListener.kt  ← XBreakpointListener，保持 service ↔ IDE 同步
    sync/
      BreakpointIdeSyncer.kt    ← checkout 核心：IDE 断点增删 + setDefaultGroup
    mcp/
      DebugMapToolset.kt        ← MCP tools，暴露给 AI 工具调用
  resources/META-INF/
    plugin.xml                  ← 插件描述符，注册 listener 和 MCP toolset
  intellij.debugmap.iml
  BUILD.bazel
```

---

## 实现进度

### 已完成

- [x] 插件骨架（IML、BUILD.bazel、plugin.xml）
- [x] `DebugMapService` 纯数据层（Group 管理、Active group、Breakpoint 定义、Stash 栈）
- [x] 持久化：`PersistedState` XML 序列化，写入 `workspace.xml`（本地，不进 git）
- [x] 代码分层：model / manager / listener / sync / mcp 各司其职
- [x] `XBreakpointListener`：监听断点增删改，双向同步 service ↔ IDE
  - `isSyncing` 标志防止 checkout 期间的事件循环
- [x] `BreakpointIdeSyncer`：checkout 核心逻辑
  - 从 IDE 删除旧 active group 断点
  - 向 IDE 添加新 active group 断点
  - 调用 `XBreakpointManagerImpl.setDefaultGroup`
- [x] MCP Toolset（`DebugMapToolset`），注册到 `mcpServer.mcpToolset` 扩展点
  - `debugmap_list_groups`
  - `debugmap_create_group`
  - `debugmap_checkout`
  - `debugmap_commit`（no-group 断点 → active group）
  - `debugmap_stash` / `debugmap_pop`

---

### 待实现

#### 1. isSyncing 并发安全

当前 `isSyncing: Boolean` 是简单 flag，存在并发竞争风险。
应改为 `ReentrantLock` 或原子计数器，确保 checkout / stash / pop 并发时不会误触发 listener。

#### 2. 彩色图标

使用 `XBreakpointManagerImpl.updateBreakpointPresentation(bp, icon, tooltip)` 为不同 group 的断点染色，checkout 时批量更新：
- 每个 group 对应一种颜色（`group.id % N` 取色）
- 只对 active group 的断点染色
- no-group 断点保持默认红色

#### 3. UI（Tool Window）

在调试工具栏旁增加 Debug Map 面板：
- Group 列表（点击 checkout）
- 当前 active group 高亮
- Stash 栈内容展示
- 新建 group / commit / stash / pop 按钮

---

## 关键 API 参考

| API | 位置 | 用途 |
|-----|------|------|
| `XBreakpointManagerImpl.updateBreakpointPresentation` | platform/xdebugger-impl | 给单个断点设置自定义图标 |
| `XBreakpointManagerImpl.setDefaultGroup(String)` | platform/xdebugger-impl | 控制新断点自动归入哪个 group |
| `XBreakpointListener.TOPIC` | platform/xdebugger-api | 监听断点增删改事件 |
| `XBreakpointUtil.findType(id)` | platform/xdebugger-impl | 按 ID 查找断点类型，用于 restore |
| `StoragePathMacros.WORKSPACE_FILE` | platform/projectModel-api | 本地 workspace.xml 存储 |

## 注意事项

- **不要**创建自定义 `XLineBreakpointType`——`XDebugSessionImpl` 用 `==` 做类型比较，子类会导致调试器无法命中断点
- **不要**把状态存到 `.idea/debugMaps.xml`（进 git，会产生冲突）——必须用 `WORKSPACE_FILE`
- inactive group 的断点要**真正删除**出 IDE，不是 disable——这是与普通 group 功能的根本区别
- `BreakpointDef.typeId` 必须持久化，checkout 时 restore 断点依赖它找到正确的 `XLineBreakpointType`
- checkout / stash / pop 必须在 EDT 上调用，`XBreakpointManager` 的写操作是 UI 线程操作
