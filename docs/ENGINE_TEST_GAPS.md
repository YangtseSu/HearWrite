# Engine 测试缺口清单（待补）

来源：2026-09-04 全项目 review（domain 层评审）。`DictationEngine` 的状态机实现经逐行核对无 P0/P1 缺陷，但以下 AGENTS.md 声明的不变量**没有测试钉住**——任何一处回归都会静默通过现有套件。下一步修复时按此清单逐项补测试。

## 现状与原因

- `app/src/test/java/org/yangtse/hearwrite/domain/DictationEngineTest.kt` 的 `FakeSpeaker` 默认 `durationMs = 0`（`DictationEngineTest.kt:29-38`）：所有取消类测试只在"间隔/暂停间隙"发生，**从不在发声中途**。
- `FakeSpeaker.stopCalls` 被记录但从未被断言；`dispose()` 完全没有测试。
- 引擎在每次状态迁移（start/pause/stop/skipToNext/goToPrevious/dispose）都调 `stopAudio()`——删掉其中任何一个调用，现有套件全绿。
- 现有 `pause during speak hold` 类测试暂停的是 parked 运行，不是活的 utterance。

## 待补测试（每项一条，虚拟时间 `runTest` + `advanceTimeBy`）

| # | 场景 | 断言 |
|---|---|---|
| 1 | `durationMs > 0` 时 pause 发生在 speak 中途 | 暂停期间不再有新 utterance 开始；resume 后当前词只重播一次、从暂停点继续 |
| 2 | `durationMs > 0` 时 skipToNext / goToPrevious 发生在 speak 中途 | 发声被停（`stopCalls` 增加），直接进入目标词流程，无串词/跳词 |
| 3 | `durationMs > 0` 时 stop 发生在 speak 中途 | 会话终止，无 stray utterance；再次 start 从新列表干净开始 |
| 4 | **`dispose()` 在 PLAYING 中调用**（模拟离开屏幕） | run 取消、无 stray speech、`stopCalls` 增加、engine 状态安全 |
| 5 | speak 取消不泄漏进下一词 | 取消后立即重新 start，第一词只播一次、没有上一词尾巴 |
| 6 | speak2 失败 | 不重试，直接进入 interval（AGENTS.md：只钉了 speak1 的 no-retry，speak2 未测） |
| 7 | 自然结束后 skipToNext / goToPrevious / stop | 均为安全 no-op，状态不变（IDLE 边界） |
| 8 | `goToPrevious` 在 index 0 | clamp 到 0（`maxOf(0, …)`），不越界 |
| 9 | speak/gap 阶段关闭 auto-next | 只有 speak2 完成后才 park；期间不提前停 |
| 10 | paused / auto-next parked 时 `setIntervalSec` | 应用到下一次 countdown（现有只覆盖 start 前与倒计时中两种） |

## 验收

- 断言**行为**（utterance 序列、时间、stop 调用计数、状态），不断言实现细节。
- 保持确定性：虚拟时间、每测试独立引擎实例。
- 跑 `./gradlew :app:testDebugUnitTest` 全绿后提交，commit message 注明补齐的条号。

## 相关契约出处

- AGENTS.md "Playback engine"：cancel/gen、speak1 no-retry、auto-next hold、`Speaker.stop()` 立即释放音频。
- `DictationEngine.kt` 类注释（每项约束的代码落点）。
