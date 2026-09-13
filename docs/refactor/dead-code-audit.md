# 无用代码审查

基于基线提交的源码、resources、版本映射及生成源码引用搜索。

## 删除

- Java 中 12 个未引用 import，搜索范围包括 `//$$` 旧版本源码分支。
- RemoteContainerUtils.ItemFetchState 的 itemId 字段及赋值构造器：键已由 fetchStates
  保存，字段无读取；保留 computeIfAbsent 的创建时机。
- ExternalModDownloader.getFileNameFromResponse：唯一提及位于已注释的旧调用中。
  当前实际文件名优先级仍为显式名称、URL 名称；同步修正文档，不改变下载行为。

## 保留，公开 API 删除应单独迁移

- OperationQueue、QueuedOperation、RegionTracker、SchematicSnapshot：无外部于该组的
  仓库调用，但均为公开类。此次不删除或更改其语义。
- IteratorManager.isNeedsRebuild、isDirtyIterator、hasBox、setDirtyRegionIterator 以及
  Pointless：没有发现现行调用，但删除将改变公开签名/可加载类集合。
- RemoteContainerUtils.tick：空方法但为公开入口，继续保留；调用也保留，避免改变类初始化时机。

## 不视为死代码

Mixin/accessor、Lombok 生成方法、反射加载的兼容接口、旧版本预处理分支。
不能仅凭普通 Java 引用计数删除这些内容。
