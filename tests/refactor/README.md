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
