# Debug Map: 影子跟踪 (Shadow Tracking) 插件架构方案

Debug Map 为 IntelliJ IDEA 带来了“分支化”的断点管理体验。不同于传统的禁用/启用模式，我们采用了**影子跟踪 (Shadow Tracking)** 架构，确保了调试上下文的极致隔离与数据的绝对安全。

## 核心哲学：影子跟踪 (Shadow Tracking)

在 Debug Map 中，**`DebugMapService` 是唯一的真相源**。IDE 原生的断点系统仅被视作当前激活分组的“投影窗口”。

### 1. 上下文隔离 (Focus Over Noise)
*   **痛点**：当项目复杂时，数百个禁用状态的断点会充斥 Gutter 栏和断点管理窗口，造成极大的视觉干扰。
*   **方案**：只有当前激活组的断点会被注册到 IDE（红色实心圆）。非激活组的断点在 IDE 中物理消失，确保开发者能 100% 聚焦于当前的调试上下文。

### 2. 数据安全性 (Data Sovereignty)
*   **痛点**：IDE 的“一键删除所有断点 (Remove All Breakpoints)”操作经常会误伤开发者辛苦建立的调试地图。
*   **方案**：非激活组的断点数据被“影子化”存储在插件私有的 Service 中。由于它们不在 IDE 的管控范围内，任何原生删除操作都无法伤及这些数据。

### 3. 多重宇宙支持 (Multi-Universe Support)
*   **痛点**：IDE 原生系统不支持同一行存在多个同类型断点。
*   **方案**：借助影子存储，同一行代码在不同分组中可以拥有完全不同的“身份”——在分组 A 中它是“打印日志”，在分组 B 中它是“条件挂起”。切换分组就像切换 Git 分支一样平滑，且互不干扰。

---

## 技术实现原理

### 影子位置同步 (Position Syncing)
为了解决非激活断点在代码修改后的行号漂移问题，我们实现了一套高性能的同步机制：
*   **RangeMarker 监听**：当包含断点的文件被加载进内存（`Document` 创建）时，插件会自动在后台为所有影子断点挂载 `RangeMarker`。
*   **自动偏移**：利用 IntelliJ 底层极其稳定的“间隔树 (Interval Tree)”算法，影子断点会随着代码的增删、粘贴、重构甚至 `git pull` 自动移动。
*   **按需写回**：只有在切换分组（Checkout）或保存项目时，同步后的行号才会被持久化写回 Service。

### 生命周期管理 (Disposable)
*   采用严格的 **家长-孩子 (Parent-Child) Disposable** 模式。
*   所有 `RangeMarker` 和监听器都挂载在 `Project` 级的 Service 下，确保在项目关闭或插件卸载时，所有内存资源被物理清除，杜绝内存泄漏。

---

## 插件结构

```
plugins/debug-map/
  src/com/intellij/debugmap/
    DebugMapService.kt               ← 单一真相源，实现 Disposable
    manager/
      BreakpointDefManager.kt        ← 影子断点容器，基于 Map 的唯一性管理
    sync/
      BreakpointMarkerTracker.kt     ← 影子同步核心，管理 RangeMarker 生命周期
      BreakpointIdeSyncer.kt         ← 投影同步核心，处理 Checkout 逻辑
    listener/
      DebugMapBreakpointListener.kt  ← 监听 IDE 操作并实时“捕获”到影子库
```

## 注意事项

- **不要**创建自定义 `XLineBreakpointType`——`XDebugSessionImpl` 用 `==` 做类型比较，子类会导致调试器无法命中断点
- **不要**把状态存到 `.idea/` 下（会进 git 产生冲突），必须用 `StoragePathMacros.WORKSPACE_FILE`
- checkout / IDE 断点写操作必须在 EDT 上执行（通过 `writeAction {}` 调用）

## 后续演进

*   **彩色复合图标**：在 Gutter 栏通过微型彩点提示该行在其他”影子分组”中也存在定义。
*   **全局 Document 监控**：将监听范围从”打开的文件”扩大到”内存中的所有 Document”，实现更高精度的后台同步。
