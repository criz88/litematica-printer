# 行为基线测试

使用项目原有生产依赖版本，测试限定在 1.21.4 的独立编译任务，不进入发布 JAR。
不修改 source set，避免版本预处理插件将测试传播至其他目标。

```sh
./gradlew -I tests/refactor/refactor.init.gradle :1.21.4:refactorTest
```

需要 JDK 25 运行构建，以及可被 Gradle 发现的 JDK 21 工具链。
认证和依赖仓库要求与普通构建相同。

`-PrefactorRecordBaseline=true` 仅在基线提交上用于创建固定配置快照。
重构后的正常验证不使用此参数，不能用重新生成快照来掩盖差异。

普通 JVM 不加载 Fabric Mixin；测试启动只屏蔽配置 UI 的两个 Mixin 扩展方法，
并用 Mockito 替代实际客户端和静态 Mixin accessor。此范围不能证明 Mixin 应用、
实际服务器收包、配置可见性或游戏内渲染正常，运行场景见 docs/refactor/passes.md。

测试配置依据 Gradle 官方文档：
[JUnit Platform](https://docs.gradle.org/current/userguide/java_testing.html)、
[初始化脚本](https://docs.gradle.org/current/userguide/init_scripts.html)。

## 实际客户端

```sh
./gradlew -I tests/refactor/client.init.gradle :1.21.4:runClientGameTest
./gradlew -I tests/refactor/client.init.gradle -PrefactorMinimalMods=true :1.21.4:runClientGameTest
./gradlew -I tests/refactor/client.init.gradle -PrefactorGameVersion=26.2 :26.2:runClientGameTest
```

一次运行一个目标。需要可用图形桌面及对应 Minecraft assets；第一次下载资源不能使用
`--offline`。世界和配置只写入 `versions/<target>/build/refactor/client-game-test`。
1.21.4 和 26.2 覆盖仓库当前使用的两种 Loom 插件；测试辅助源码仅在 build 目录适配 Fabric
GameTest 接口名称，不升级生产依赖。退出码非零或没有通过标记时不能判定成功。

测试通过标记为 `REFACTOR GAME PARITY PASSED`；复杂方块另输出
`REFACTOR PLACEMENT PASSED`。断言包含服务器实际方块状态。
模块调度、远程箱子和潜影盒分别输出 `REFACTOR MODULE PARITY PASSED`、
`REFACTOR REMOTE PARITY PASSED` 和 `REFACTOR SHULKER PARITY PASSED`。
最小模组运行只加载普通放置和模块调度用例，运行类路径排除可选模组，编译依赖不变。
这些样例并不覆盖 passes.md 的全部可选模组组合。

### 额外可选模组

准备独立目录，仅放入已验证的 Fabric 26.2 发布 JAR：
[TakeItOut 1.1.27-26.2](https://modrinth.com/mod/takeitout/version/3Ij1zxdF) 和
[BedrockMiner v1.6.1-mc26.2](https://modrinth.com/mod/next-fabric-bedrock-miner/version/pFPAQLRF)。
下载时校验发布元数据中的 SHA-512，不修改生产依赖声明。

```sh
./gradlew -I tests/refactor/client.init.gradle -PrefactorGameVersion=26.2 \
  -PrefactorOptionalModsDir=/absolute/path/to/test-mods :26.2:runClientGameTest
```

除核心用例外，应出现 `REFACTOR OPTIONAL PARITY PASSED`。这里明确验证 TakeItOut
在现有桥接下拒绝请求、物品不变且不等待，以及 BedrockMiner 入队后被挖沟切换取消。
不是 TakeItOut 成功取料或完整破基岩测试；接口适配另见 `docs/refactor/migrations.md`。

### Paper/AxShulkers PLUGIN

使用新的临时服务器目录，不得指向日常游戏存档：测试会更改地形、清空测试玩家背包并移动物品。
夹具固定连接 `127.0.0.1:25580`，RCON 使用 `127.0.0.1:25581`，玩家名为 `PrinterRefactor`。
采用 Paper 1.21.4 build 232 和
[AxShulkers 1.23.3](https://modrinth.com/plugin/axshulkers/version/C8cxk94a)。
Paper 官方发布 JAR SHA-256 为
`5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc`。
服务器下载与启动遵循 [Paper 官方指南](https://docs.papermc.io/paper/getting-started/)。

将 Paper 放为 `server.jar`，插件放入 `plugins/`。本次测试使用插件默认配置。
服务器属性设为：

```properties
server-ip=127.0.0.1
server-port=25580
online-mode=false
enforce-secure-profile=false
enable-rcon=true
rcon.port=25581
allow-flight=true
spawn-protection=0
level-type=minecraft:flat
generate-structures=false
max-players=1
view-distance=3
simulation-distance=3
```

在同一目录生成随机 RCON 密码，写入 `server.properties` 的 `rcon.password`，并将
相同原文保存为 `rcon.password` 文件（不含换行）；两个文件权限均设为 `0600`。
完成服务器 EULA 设置后，在该目录使用 Java 21 启动
`java -Xms512M -Xmx2G -jar server.jar --nogui --noconsole`。
确认隔离服务器已就绪，再运行：

```sh
./gradlew -I tests/refactor/client.init.gradle -PrefactorMinimalMods=true \
  -PrefactorPluginServerDir=/absolute/path/to/test-server :1.21.4:runClientGameTest
```

此参数只启用 `PluginShulkerGameTest`；成功需同时有 `REFACTOR PLUGIN PARITY PASSED`
和成功退出。完成后通过本地 RCON `stop` 停止夹具服务器。JAR、存档、密码不提交到仓库。

## 产物和构建约定对照

先在基线提交构建，再捕获到不会被后续构建覆盖的目录：

```sh
python3 tests/refactor/artifacts.py capture /tmp/printer-baseline/artifacts.json
./gradlew -I tests/refactor/build-parity.init.gradle --no-configure-on-demand \
  refactorBuildSnapshot -PrefactorBuildSnapshotPath=/tmp/printer-baseline/dependencies.json
```

在重构提交构建后运行 `artifacts.py compare /tmp/printer-baseline/artifacts.json`，
并将新的 `refactorBuildSnapshot` 写到另一文件，比较解析后的 JSON 内容。
`JAVA_HOME` 指定支持所有目标字节码的 javap；采集脚本拒绝覆盖原有产物基线。
配置及放置快照也应在基线生成，重构后不得用重新录制代替调查差异。

详细结果、运行命令与本机依赖认证限制见 `docs/refactor/validation.md`。
