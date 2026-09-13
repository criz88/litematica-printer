# 重构验证记录

基线：`cc8d82754d3a7fb2496178e4170b30f373ca8163`。
生产代码审计至：`abed6fc5`。未升级框架、依赖、公开 API、配置格式或网络协议。

## 已有证据

| 检查 | 结果 | 证据 |
|---|---|---|
| 所有 15 个目标及 wrapper 构建 | 通过 | 最终 `/private/tmp/printer-refactor-final-build.log`，BUILD SUCCESSFUL，110 tasks |
| 行为测试 | 26 项通过，0 失败/错误 | 最终日志及 `versions/1.21.4/build/test-results/refactorTest/TEST-*.xml`；独立任务 `:1.21.4:refactorTest` |
| 新增远程回调测试 | 4 项在重构前后均通过 | `/private/tmp/printer-refactor-original-remote-tests.log`、`/private/tmp/printer-refactor-remote-tests.log` |
| 放置规则固定输入 | 2436 个输入，753 个非空动作，132 组摘要一致 | `tests/refactor/fixtures/placement-baseline.json`、PlacementParityTest |
| 公开兼容性 | 1920 个公开/受保护类签名保持不变 | artifacts.py 对比原始产物；包含 Lombok 访问器 |
| 资源与 wrapper | 15 个嵌套 JAR；mod 元数据、Mixin 配置和翻译保持一致 | 同一 artifacts.py 检查 |
| 构建约定提取 | 15 目标仓库顺序、发布坐标、声明/强制/解析依赖与解析 JAR SHA-256 一致 | baseline/dependencies.json 与 after-dependencies.json 的解析内容完全相同 |
| 1.21.4 实际客户端 | 通过 | `/private/tmp/printer-refactor-client-1.21.4.log`，显式 REFACTOR GAME PARITY PASSED 和 BUILD SUCCESSFUL |
| 26.2 实际客户端 | 通过 | `/private/tmp/printer-refactor-client-26.2.log`，相同标记和 BUILD SUCCESSFUL；Java 25、非 remap Loom |
| 1.21.4 扩展客户端场景 | 通过 | `/private/tmp/printer-refactor-client-1.21.4-expanded.log`：16 次复杂放置、6 次状态修正，以及 MODULE/REMOTE/SHULKER 通过标记 |
| 1.21.4 仅必需模组 | 放置与状态修正通过 | `/private/tmp/printer-refactor-client-1.21.4-minimal.log`：Fabric Loader 的实际模组清单不含 Tweakeroo、QuickShulker、RemoteInventory、ModMenu |
| 26.2 扩展客户端场景 | 通过 | `/private/tmp/printer-refactor-client-26.2-expanded.log`：复杂放置、修正、实际模块、远程箱子和 QuickShulker MOD |
| 26.2 仅必需模组 | 通过 | `/private/tmp/printer-refactor-client-26.2-minimal.log`：复杂放置、修正、水/冰、等待中移动和实际模块 |
| 26.2 额外可选模组 | 通过所列场景 | `/private/tmp/printer-refactor-client-26.2-optional.log`：全部核心场景、水/冰、转向取消；TakeItOut 原有拒绝行为和 BedrockMiner 实际任务取消；BUILD SUCCESSFUL，1m 28s |
| Paper/AxShulkers PLUGIN | 通过 | `/private/tmp/printer-refactor-client-1.21.4-plugin.log`：PLUGIN PARITY PASSED；BUILD SUCCESSFUL，25s |
| 测试与发布隔离 | 通过 | 最终 wrapper 的 15 个嵌套 JAR 均不含 `refactor/` 客户端测试类 |

1.21.4 客户端用例加载真实 Fabric/MaLiLib Mixin、保存和重新加载配置，
使用真实 Module、IteratorManager、PlacementGuide 和 ActionManager 放置石块，
通过 BreakUtils 挖掘。分别覆盖直接交互和数据包模式；等待 tick 后同时断言
服务器和客户端的方块状态，避免将客户端预测误当成服务器成功。
测试在 `versions/<target>/build/refactor/client-game-test` 创建独立世界。
26.2 使用同一基础场景。测试脚本须在 Minecraft 版本声明后配置 GameTest source set，
并适配 26.2 Fabric API 的 context 包及 getClientLevel 名称；未修改生产构建脚本。

复杂放置覆盖楼梯、门、活版门、双格植物、漏斗、箱子、中继器和台阶，逐一断言
服务器完整 BlockState；门和双格植物另检查上半部生成。门、活版门和中继器
还通过真实点击恢复目标状态。以上均运行直接交互和数据包两种模式。
水/冰场景验证创造模式跳过、生存模式放冰、挖掘生成水源以及放入含水台阶；
另验证水平转向等待期间移出范围会取消排队动作。两个版本完整环境和 26.2 最小环境通过。

ModuleLifecycleGameTest 使用实际 ModuleManager，验证填充、超出工作范围暂停、
返回后恢复、挖沟期间屏蔽已启用的填充，以及关闭挖沟后恢复填充。
RemoteContainerGameTest 使用实际选区、网络和集成服务器，从空缓存扫描箱子取料，
再填满背包，验证旧材料回塞、新材料取入及重置；数量由服务器背包和箱子共同确认。
ShulkerGameTest 通过真实 QuickShulker MOD 接口取料，检查延迟关闭和空鼠标槽，
移动两个内容相近的盒子后分别验证精确回塞追踪原盒、非精确回塞使用首个可用盒。
PluginShulkerGameTest 在隔离的本地 Paper 1.21.4 build 232 / AxShulkers 1.23.3
服务器重复取料和两种回塞场景，通过 RCON 查询服务器物品数量；测试结束后服务器已停止。
OptionalIntegrationGameTest 使用 TakeItOut 1.1.27-26.2 和 BedrockMiner v1.6.1-mc26.2，
验证前者保留基线拒绝行为，后者实际入队后因挖沟切换停止并清空队列。
测试提供生存模式、效率 V 工具、急迫效果、活塞和红石火把，满足依赖的入队前提。

远程回调 JVM 用例使用依赖中的真实缓存和回塞追踪器，验证部分成功、全部成功、
失败使取料缓存失效，以及迟到响应仅解除等待。未模拟真实远程服务器协议。
重构前对照使用 git show 导出的原始 RemoteContainerUtils，按源码既有版本分支
选用 1.21.4 的 ResourceLocation 名称；以独立 JavaCompile 输出覆盖测试类路径，
没有修改工作区生产文件。

## 环境限制和未验证范围

- 普通 GitHub Maven 依赖解析返回 HTTP 401。验证使用已有本地同坐标 JAR，
  没有改项目依赖声明；只能证明在相同依赖输入下的前后行为与构建一致，不能证明
  当前远端认证或重新下载可用。详见 compatibility-matrix.md。
- 普通 JVM 用例以测试替身替代客户端和静态 Mixin accessor；其通过不能证明实际网络、
  GUI 可见性或渲染。真实客户端用例补充其中一部分证据。
- TakeItOut 的已有 API 兼容限制已复现，见 migrations.md；其成功取料尚不支持此已检查版本，
  功能迁移不混入行为保持重构。BedrockMiner 只验证桥接和任务取消，没有运行完整破基岩操作。
- 实际客户端覆盖 1.21.4 和 26.2；其他版本仅构建/产物对照。错误方块动作、容器异常回调、
  FIFO 和重试上限由 JVM 测试覆盖，未穷举实际服务器上的全部错误条件或 Tweakeroo 配置。
- 对比有意忽略构建时间戳、ZIP 时间及新增内部类；不忽略公开签名、配置键或动作差异。

## 本次环境下的可复现命令

构建运行于已有 JDK 25，使用 JDK 21 工具链；独立 Gradle home 避免与 IDE 的 Loom 锁冲突。
下面的本地验证 init 脚本和缓存位于本机临时目录，不是项目正式依赖来源。

```sh
JAVA_HOME=/private/tmp/printer-jdk25/jdk-25.0.4.1+1/Contents/Home ./gradlew \
  -g /private/tmp/printer-refactor-gradle-home \
  -I /private/tmp/printer-validation.init.gradle \
  -I tests/refactor/refactor.init.gradle \
  :1.21.4:refactorTest :fabricWrapper:build --offline --console=plain \
  -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

JAVA_HOME=/private/tmp/printer-jdk25/jdk-25.0.4.1+1/Contents/Home \
  python3 tests/refactor/artifacts.py compare /private/tmp/printer-refactor-baseline/artifacts.json
```

通用测试入口和基线采集命令见 `tests/refactor/README.md`。
