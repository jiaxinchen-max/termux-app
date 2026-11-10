# 统一 Wine 私有存储路径

## 目标
将 front/containers 更名为 front/wine，并将当前位于 usr/var/lib/proot-distro/games-template-cache 的 Proot 预构建 prefix 压缩包及配套缓存收纳到 front/wine/templates。不做旧目录迁移、兼容读取或自动重建。

## 范围
同步 Java 路径、Shell 脚本、备份恢复、空间统计、孤儿清理和测试。已有容器记录与工作目录保持相对结构。templates 为共享资产保留目录，不能作为容器处理或随单个游戏删除。共享 RootFS 与桌面 proot-distro 目录不变；Native prefix 组件安装和下载位置本次不变。

## 文档
无 HTTP 变更，api_docs=N/A。存储路径对文件浏览和手动管理可见且不兼容旧布局，product_docs=Required，目标 docs/product/wine-storage.md，验收前一致性 Pending。待用户确认方案后实施。
