# AGENTS.md · 项目级 Agent 记忆与工程红线

> 本文件是**项目级 Agent 记忆**，优先级高于临时任务指令。任何 Agent 在本仓库工作前必须先读本文件、
> `docs/00-conventions.md` 与 `docs/02-client-modules.md`。若任务要求违反本文件，先向用户/Lead 确认，不得擅自破坏。

## 1. 最高优先级红线：架构与模块解耦

**当前项目开发必须始终保证架构清晰、模块解耦。** 任何功能、性能修复、实验性管线都不得以“先写通再说”为由跨层直连。

### 1.1 模块与依赖方向（单向）

```
app-ui (Compose) ──> app-core (P1Controller / P1UiState 契约)
app-service ──> app-core
app-streaming (采集/编码/推流引擎) ──> app-core + lan-core
app-camera (相机实现) ──> app-core
lan-core (纯 JVM 端侧服务) ──> 无 Android 依赖
```

- 依赖只能**自上而下单向**：`app-* → lan-core`，禁止 `lan-core` 反向引用 Android/app 代码。
- `app-ui` 只允许依赖 `com.campush.app.core` 的契约（`P1Controller`、`P1UiState`、枚举）；
  **禁止**直接 import `camera/`、`streaming/`、`service/`、`network/` 的内部实现类。
- `app-core` 不得依赖具体相机/编码器实现；需要新能力时向 `app-streaming` / `app-camera` 新增接口。
- `lan-core` 对外只暴露 `com.campush.lan.Api.kt` 中的冻结契约，新增能力先改契约再实现，保持纯 JVM、可单测。

### 1.2 可替换管线必须隐藏在抽象后

- 采集/编码管线（CameraX ImageAnalysis、Camera2 + MediaRecorder Surface、未来 WebRTC/GL）必须通过统一管线接口接入，
  `LanStreamEngine` 不得把某一种管线的生命周期/类名写死到 UI 或业务逻辑中。
- 新增管线 = 新增一个实现 + 适配器，**不得**在 UI/控制器里加重载分支。
- 管线失败必须可回退（当前：Surface 管线失败 → CameraX 兼容管线），回退不改变播放地址/契约。

### 1.3 契约冻结

- 播放地址唯一、停止推流即失效；错误码 `3002`（token 失效）与 `3003`（PIN 错误）语义不得混用；
- 局域网停止 ≤1s；
- UI 状态只能通过 `P1UiState` 暴露，不允许 UI 直接读取引擎/相机单例的内部字段。

## 2. 模块职责速查

| 模块 | 目录 | 允许依赖 | 禁止 |
|---|---|---|---|
| app-ui | `app/src/main/kotlin/.../ui/` | `core` 契约、Compose、Android UI | 直接 new 相机/编码器、访问 Engine 内部 |
| app-core | `.../core/` | `lan-core` 契约、Android 基础 API | 依赖具体采集/编码实现类 |
| app-streaming | `.../streaming/` | `core`、`lan-core`、`camera` 接口 | 依赖 UI |
| app-camera | `.../camera/` | `core` 契约、CameraX/Camera2 | 依赖 UI、直接发布媒体 |
| app-service | `.../service/` | `core`、`streaming` 引擎接口 | 依赖 UI |
| lan-core | `lan-core/` | Kotlin/JVM 标准库 | Android SDK、app 模块 |

## 3. 修改流程与验证门禁

1. 先读 `docs/00-conventions.md` 与 `docs/02-client-modules.md`；
2. 跨模块改动先更新契约（`P1Contracts.kt` / `lan-core/Api.kt`），再写实现；
3. 必须通过：
   - `scripts/build.sh :lan-core:test :app:assembleDebug`
   - `node tools/player-selftest/run.mjs`（播放页改动时）
   - `python3 scripts/check_docs.py --strict`（文档改动时）
4. 涉及架构/契约的改动必须同步 `docs/` 与 `README.md` 变更记录，并在回复中说明依赖方向是否变化。

## 4. 当前性能管线决策（供后续 Agent 参考）

- 默认：**Camera2 + MediaRecorder(SURFACE, MPEG_2_TS) → TsDemuxer → MediaHub → FLV**，App 内 SurfaceView 原生预览；
- 回退：CameraX `Preview + ImageAnalysis` 兼容管线（API < 26 或 Surface 管线初始化失败）；
- 禁止为了帧率在 UI 层直接操作相机/编码器；所有优化在管线实现内完成，通过 `P1UiState` 汇报实际分辨率/fps/警告。

## 5. 文档与证据

- 工程事实以 `docs/` 为唯一来源；实现细节更新 `docs/12-p1-implementation.md`；
- 性能/兼容性结论必须附可复现步骤或日志字段，不得只写“已优化”；
- 用户可见行为变更必须更新 README 变更记录。
