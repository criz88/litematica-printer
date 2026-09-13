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
