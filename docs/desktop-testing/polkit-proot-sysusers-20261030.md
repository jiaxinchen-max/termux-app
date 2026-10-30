# 容器标准包工具与真实 mount ID 修复

目标为 `BUILD_HOST:8037 / XIAOMI_SERIAL` 的 `debian-kwin-plasma-1`。正式修复由 APK 的生产脚本执行，设备诊断只读。

## 原因

旧安装器将 15 个包的 postinst 改成两行 no-op；其中 `polkitd 128-1` 虽显示 installed，却缺少服务用户/组。原始 postinst 调用标准 `systemd-sysusers polkit.conf`，该设备的 statx 不返回 mount ID，systemd 262 因此报 `Protocol driver not attached`。预建账户不能解决标准工具的兼容问题。

## 最终生产路径

- 安装助手显式传 `--statx-backend=proc-fdinfo`，会话显式传 `DESKTOP_STATX_BACKEND=proc-fdinfo`。缺失、未知参数退出 64，无其他后端。
- 保留标准 `systemd-sysusers`、`systemd-tmpfiles`、APT 和 dpkg。自有旧 opensysusers diversion 仅在 manifest、源码 SHA、wrapper SHA、diversion owner/path、原二进制包内 MD5 全部匹配后退役；未知状态退出 65。
- 包配置子进程通过 `run-config --statx-backend=proc-fdinfo` 显式加载 native 适配库。先实测唯一 probe marker，防止动态加载器忽略库却返回成功；不向会话父进程、Qt 或 renderer 注入 preload。
- 对请求 mount ID 的 statx，先打开目标 O_PATH FD，再从同一 FD 获取原始 statx 元数据和 `/proc/self/fdinfo` 的真实 `mnt_id`。支持 relative/nofollow/unlinked/AT_EMPTY_PATH；只声明普通 MNT_ID，清除 UNIQUE。fdinfo 失败保留原错误，不改用内核 ID 或其他来源。
- APT `Dir::Bin::dpkg` 显式指向 `dpkg-procfdinfo`，覆盖安装器和 PackageKit 后续 libapt 调用；run-config 包配置子进程显式使用 `DEBIAN_FRONTEND=noninteractive`。final-v2 同时在 restore_legacy_postinsts 自有子shell导出该变量，覆盖发生在 dpkg wrapper 之前的 APT/dpkg-preconfigure 阶段；父进程及 Qt 环境不变。PackageKit daemon 本身无需 preload。
- 识别确切历史两行 no-op 后，逐包下载已安装的同版本、同架构签名 APT 包；模拟计划必须零升级、零删除；真实重装后比对原 deb control/postinst 并核验 configured 状态。失败保留退出码，不重试或改写维护脚本。证据写 `/var/lib/termux-proot-statx/legacy-postinst-verification.txt`。
- 校验现有 PolicyKit UID/GID 非 root、primary GID 与命名组一致、服务 home、nologin、锁定密码。按标准目录优先级读取有效 polkit.conf，仅接受单一 u/u! 条目；forky 128 的 u! 必须 expiry=1，trixie 126 的 u 允许标准空 expiry 或已有锁定值1。未知/重复定义和异常账户直接失败；账户创建仍由标准 systemd-sysusers 执行。认证规则和 daemon privilege drop 不变。

15 包：`bluez`、`dbus-system-bus-common`、`dnsmasq-base`、`fwupd`、`network-manager`、`openssh-client`、`pipewire:arm64`、`polkitd`、`rtkit`、`systemd-coredump`、`systemd`、`tpm-udev`、`udev`、`udisks2`、`wpasupplicant`。`dpkg --audit` 为空只是附加门禁，不证明维护脚本完整。

## 远端构建与来源

仅在指定编译机 `builder@BUILD_HOST:7022` 的独立 arm64 rootfs 构建，宿主不编译：

```sh
cc -std=c11 -O2 -fPIC -shared -Wall -Wextra -Werror \
  -Wl,-z,relro,-z,now -Wl,--no-undefined \
  -o libstatx-procfdinfo.so container_statx_procfdinfo.c -ldl
```

- 源文件：`termux-ui/src/main/cpp/container_statx_procfdinfo.c`。
- 源 SHA256：`6a4b8fad3d8a8f2f1ed97d8e93ff5ecb250c077ccf8ac3ac914fb428d2451adf`。
- 随 APK 的 `.bin` SHA256：`92c351c40c267ec620cce42a9ae97f0ccd4600cff91848130f33ae5557e87fce`；部署为 `/usr/local/lib/termux-proot/libstatx-procfdinfo.so`。
- `readelf`：ELF64 little-endian AArch64 DYN；所需最高 GLIBC_2.38，满足 trixie 2.41 / forky 2.42。

## 验证状态

- `test-desktop-scripts.sh` 契约测试通过，覆盖显式参数、原退出码、原 APT 失败不重试、不改原 postinst、升级计划拒绝、u/u! 身份语义、未知/重复定义、renderer/输入/停止等。
- 独立 native 测试通过：相对/软链/不同 mount/unlinked FD、空/NULL AT_EMPTY_PATH、inode/dev/size 一致、不声明 UNIQUE、ENOENT/EBADF/EFAULT、错误 mode=64。强制 fdinfo EACCES 时即使内核原本提供 ID 仍保留 EACCES。
- 编译机的 stock proot 5.4.0 对 `readlinkat(fd, "")` 绝对 symlink 有独立断言缺陷：原生 x86_64、无 QEMU/适配库也复现。显式修正后的测试 runner 已通过原 `systemd 262` postinst 和 tmpfiles，设备现有 proot 同一 probe 已通过；[独立诊断](proot-runner-readlinkat-20261030.md) 记录修补和语义验证。未改生产设备 proot。
- 15 包真实恢复在编译机独立复制的 `legacy-postinst-rootfs-test` 全部通过：签名 APT 同版本重装，原 deb control/postinst 逐字节一致，15 条原脚本哈希证据，全部 configured 且 dpkg audit 为空。日志 `final-standard-tools-fixed-proot-clean-test.log`。
- 最终 setup 追加验证有效 u! 身份、全迁移幂等和显式 noninteractive 子进程环境；trixie u 空 expiry、未知/重复定义由契约测试覆盖。
- 正式 App UI 验收由主线程执行；rootfs/native 测试不代替 UI 验收。

## final-v2 前端覆盖补充

初版 final 只在 run-config 中设置 frontend，遗漏恢复函数直接执行 APT 时的前置 dpkg-preconfigure。final-v2 在恢复子shell入口显式 export，未更改维护脚本、恢复算法或原错误处理。

`test-proot-restore-frontend.sh` 在独立 rootfs 将 polkitd 设为历史 no-op fixture，父环境刻意设为 dialog，直接调用生产 restore 函数执行真实签名 APT 同版本重装。APT Pre-Install-Pkgs 实际子进程 hook 核验 noninteractive；恢复后父环境仍为 dialog、无 preload 泄漏，原 postinst 字节恢复且 audit 为空。日志为 `final-v2-real-restore-frontend-test.log`。脚本契约另核验 download/simulation/install 三个 APT 子进程都收到 noninteractive，并保持原失败码和父环境。
