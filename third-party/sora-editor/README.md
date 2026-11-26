# Sora editor 源码依赖

AndroidIDE 指定的 `0.23.4-ce8de8e-SNAPSHOT` 对应源码提交 `ce8de8e1af88abacf807068ecedf7b9b2521aa15`。原 Sonatype snapshot 元数据在 2026-11-26 返回 HTTP404，因此直接编译该固定提交，避免依赖可变或失效 snapshot。

保留上游 editor/src、consumer-rules.pro 及 LGPL2.1 许可证。upstream-lock.json 记录原始文件 SHA256，宿主构建适配仅在本目录 build.gradle；升级时重新核对源码和许可。此模块仅提供编辑器内核，不代表 AndroidIDE 语义服务已接入。宿主最低 API21 不变。
