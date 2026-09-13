# 独立迁移任务

本轮结构重构以 `cc8d82754d3a7fb2496178e4170b30f373ca8163` 为行为基线。
以下项目涉及功能或兼容策略变化，应各自审查和发布。

## TakeItOut 数据包 API 适配

实际检查了 TakeItOut 的 Fabric 26.2 发布版 1.1.25 和 1.1.27，
两者的 `GetShulkerStackPayload` 构造器均为 `(int, int, boolean)`。
仓库现有 `TakeItOutCompat.init()` 仅查找 `(int, int)`，因此解析失败后清空反射句柄，
`tryExtract` 返回 false。该文件相对重构基线没有修改；这是已有兼容限制。

发布元数据来源：
[TakeItOut 1.1.27 Fabric 26.2](https://modrinth.com/mod/takeitout/version/3Ij1zxdF)、
[TakeItOut 1.1.25 Fabric](https://modrinth.com/mod/takeitout/version/5WOYTJ3l)。
构造器签名来自对应官方发布 JAR 的 javap 检查，下载内容已对照 Modrinth SHA-512。

后续任务：适配新构造器，明确 singleItemMode 参数的配置来源，并保留旧版协议兼容。
验收需要覆盖单件/整组取料、服务器拒绝、等待标记清理，以及关闭自动取料时的行为。
本轮测试保留旧行为：请求被拒绝，来源物品不变，没有遗留等待标记。

## 公开无调用代码的弃用

OperationQueue、QueuedOperation、RegionTracker、SchematicSnapshot 等公开类型
仓库内缺少现行调用，但不能据此排除外部消费者。若要删除，应先确认消费者、
发布弃用说明并建立二进制兼容基线；必要时安排主版本迁移。

## 构建和架构迁移

- Minecraft/Fabric/Loom/Gradle/Java 升级或停用旧版本：单独调整兼容矩阵，重新采集
  依赖和产物基线，并验证 Mixin 与各版本运行环境。
- 替换版本预处理器：先定义生成源码与资源的等价检查，再迁移版本图和映射文件。
- 全局状态改为依赖注入、重写 tick 调度或网络协议：先明确生命周期、线程、顺序和恢复
  契约；使用现有逐 tick 与实际客户端场景作为迁移前后对照。
- 修改配置键、默认值或存储格式：提供旧配置读取、升级和回退策略，独立于结构提取提交。
