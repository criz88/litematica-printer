# 构建与兼容矩阵

来源：settings.gradle.kts、build.gradle.kts、buildSrc 的 javaVersion 和各版本 gradle.properties。
主源码版本：1.21.11。Gradle wrapper：9.6.1；CI 使用 JDK 25；buildSrc 工具链要求 JDK 21。

| 目标 | 字节码 Java | 构建 |
|---|---|---|
| 1.18.2、1.19.4、1.20.1、1.20.2、1.20.4 | 17 | Loom remapJar |
| 1.20.6、1.21.1、1.21.3、1.21.4、1.21.5、1.21.8、1.21.10、1.21.11 | 21 | Loom remapJar |
| 26.1.2、26.2 | 25 | Loom jar |

完整构建入口：`./gradlew :fabricWrapper:build --console=plain`。
wrapper 应包含 15 个子 JAR，fabric.mod.json 中的 jars 引用与实际内容一致。
比较产物时忽略构建时间、开发版时间戳、ZIP 时间及仅因重构增加的内部类。
保留依赖坐标、版本、仓库顺序、资源、公开签名及 Mixin 配置。

## 可选集成运行矩阵

- 仅必需模组：Fabric API、MaLiLib、Litematica。
- Tweakeroo 存在/缺失：工具选择和限制名单。
- QuickShulker MOD、PLUGIN 路径，以及对应版本支持的 TakeItOut。
- RemoteInventory 存在/缺失：缓存命中、扫描、交换、回塞与重置。
- BedrockMiner 存在/缺失：挖沟切换与破基岩任务。

各组合应在实际支持它的版本运行；不能把无此集成的旧版本标成通过。

## 环境与基线结果

- 默认 Java 17；另发现已有 JDK 21 和 JDK 25，可用于验证，不修改项目版本。
- 首次构建因沙箱禁止写 Gradle 缓存失败；允许缓存访问后继续。
- 指定已有 JDK 21 工具链后，离线构建因缺少 remote-inventory-next 缓存失败。
- 环境恢复及后续结果记录在 passes.md；离线依赖失败不算源码基线通过。

- 普通联网构建：GitHub Maven 返回 HTTP 401（GH_USERNAME/GH_TOKEN 未设置）。
- 本地验证构建：使用已有 /private/tmp/printer-validation.init.gradle 指向同坐标本地 JAR；
  未修改项目依赖声明，基线完整构建通过（15 个目标）。这些缓存的远端来源尚未重新认证验证。
- 基线独立行为测试 6 项通过，其中一项穷举 48 种遍历顺序。
- 完整日志：/private/tmp/printer-refactor-baseline-full.log。测试任务为 :1.21.4:refactorTest。

## 重构后结果

- 15 个目标及 wrapper 在相同本地验证依赖下完整构建通过。
- 原有 22 项行为测试通过；新增 4 项远程回调测试在重构前后均通过。
- 1920 个公开/受保护类签名、资源、依赖校验值、仓库顺序和发布坐标一致。

| 实际运行环境 | 已验证范围 |
|---|---|
| 1.21.4、26.2，仓库完整模组环境 | 配置 Mixin 和保存/加载；两种模式下放置/挖掘、复杂方块和状态修正；模块暂停/恢复/挖沟独占；远程扫描取还；QuickShulker MOD 精确/非精确回塞 |
| 1.21.4，仅必需模组 | 放置、挖掘和状态修正；实际加载清单确认可选模组缺失 |
| 26.2，仅必需模组 | 上述基础场景，另含水/冰、转向等待中移出范围和模块调度 |
| 1.21.4，Paper 1.21.4 build 232 / AxShulkers 1.23.3 | QuickShulker PLUGIN 取料、盒子移动后精确回塞和非精确回塞；服务器确认数量 |
| 26.2，TakeItOut 1.1.27 / BedrockMiner 1.6.1 | TakeItOut 保持已有拒绝行为，无传输或遗留等待；BedrockMiner 实际入队及挖沟切换后停止/清队列 |

完整环境的水/冰和转向取消也在两个版本通过。Tweakeroo 的存在/缺失已运行，
但未穷举其工具限制配置；BedrockMiner 的检查不包括完成破基岩机械操作。
没有将两个实际运行版本推广为全部 15 版本的游戏验证。详见 validation.md。
