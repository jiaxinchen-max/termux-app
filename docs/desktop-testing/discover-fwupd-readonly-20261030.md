# Discover fwupd 提示只读归因

目标 dpkg 状态文件确认：fwupd/libfwupd3 `2.1.8-1`、GLib `2.90.1-2`、plasma-discover 与 fwupd backend `6.7.4-3+termuxfix1`。调查仅读取目标文件，未启动或调整容器会话。

精确版本调用链：

1. Discover `FwupdBackend.cpp:360-361` 在 libfwupd >=2.0 分支调用 `fwupd_client_refresh_remote_async()` 刷新已启用远端。
2. fwupd 2.1.8 `libfwupd/fwupd-client.c:5010` 下载元数据签名；`:6384-6387` 在 HTTP 下载前调用 `fwupd_client_test_network()`。
3. `:6276-6284` 调用 `g_network_monitor_get_default()` 与 `g_network_monitor_can_reach()`。后者为 false 时返回 `FWUPD_ERROR_NOT_REACHABLE`，消息为 `network is unreachable: %s`，不会进入 curl 下载。
4. Discover `FwupdBackend.cpp:344-346` 取回错误，`:245-257` 的 `handleError()` 将其作为 passiveMessage 显示。

因此已记录的 GIO.can_reach=false、curl 可达可以同时成立：fwupd 在 HTTP 请求前被 GIO 的可达检查拒绝。现有证据将故障定位在容器环境中的 GIO 可达性判断，尚不能据此声称具体底层路由/内核原因已经确认。

对比结果：FwupdBackend 的全部 9 个文件（包括 CMakeLists、Backend、Resource、SourcesBackend、Transaction）与 Debian 6.7.4 原始源码逐字节相同。Debian 6.7.4-3 的原两个补丁不涉及该目录；fwupd 2.1.8-1 的两个 Debian 补丁仅改变 CI 构建依赖和 p2p 测试，未修改上述网络判断。这里的零差异指 backend 源码，不声称重新编译后的 ELF 散列相同。

fwupd 原始源码归档 SHA256：`29ce4c6d7c5211f487474219474b422132b58f03adedf826816b9c8cce129274`。

fwupd Debian tar SHA256：`4bfb512bfd6e506f13a153114816ada5d3dcfd7883e2144bf537d9b00dcd1826`。

FwupdBackend.cpp 原版/本地版共同 SHA256：`c73b5a8778ceaeb6fd972e7fb7c655e362679db5a27c6fdce670d96817a6d921`。

本次 Discover 补丁仅修正 QML 离线提示的生命周期；未修改 fwupd backend、未忽略 GIO 错误、未伪造网络状态。此 fwupd 固件元数据刷新提示应与 PackageKit 应用搜索/安装/卸载结果分别记录。
