# 独立恢复测试 runner：readlinkat 空路径断言

## 范围与根因

15 包历史 postinst 恢复 harness 使用 Debian `proot_5.4.0-3_amd64.deb` 和 QEMU。真实 `systemd-tmpfiles` 触发：

```
proot: ./path/path.c:547: compare_paths2: Assertion `length2 > 0' failed.
```

原生宿主最小程序仅执行 `open(symlink, O_PATH|O_NOFOLLOW)`、`readlinkat(fd, "", ...)`，在该 proot 下即可复现；无需 QEMU、statx shim 或 rootfs。相对 symlink target 成功，绝对 target 触发断言。`enter.c` 保留合法空路径，`exit.c` 将空 referrer 传给 `detranslate_path()`，后者的 `/proc` 比较要求非空 referrer。

仅修补独立 runner：空路径且返回绝对 target 时，从 `/proc/<tracee pid>/fd/<fd>` 恢复真实 referrer，继续使用原 rootfs/bind detranslation。解析错误照常返回；不关闭断言，不吞错误，不增加模式回退。生产 App statx 后端与设备 proot 均未改动。

补丁：`patches/proot-5.4.0-readlinkat-empty-runner.patch`。
最小探针：`native-tests/proot-readlinkat-empty.c`、`native-tests/proot-readlinkat-errors.c`。

## 构建

所有编译在已授权远端 `builder@BUILD_HOST:7022`，未在本地编译或安装宿主系统包。独立目录：

```
/tmp/xserver-proot-diagnosis-20261030
```

源包 `https://deb.debian.org/debian/pool/main/p/proot/proot_5.4.0.orig.tar.gz`，SHA256：

```
29248aac2a7ce10c3bd5ee5602742ec33b2532310ff9cf73b79f3c133e5a5f68
```

在自有 `deps/` 解包 `libtalloc-dev_2.4.3+samba4.23.6+dfsg-1ubuntu2_amd64.deb`、`uthash-dev_2.3.0-2build1_amd64.deb`。对应 SHA256：

```
df8f7ba7e4d0cc7236ac23ecf74214ce31c76feb6a1e5c85e167ff11603d40bd
c0c12bea64425f468d77b85eaf5176a50daa997813357cfffffc6a934f8ec1ad
```

`libtalloc.so.2.4.3` 从原 runner 的 tools 目录复制到自有 deps；在源码 `src/` 中执行：

```sh
b=/tmp/xserver-proot-diagnosis-20261030
make -j4 HAS_SWIG= HAS_PYTHON_CONFIG= \
 CFLAGS="-g -O2 -Wall -Wextra -I$b/deps/usr/include" \
 LDFLAGS="-L$b/deps/usr/lib/x86_64-linux-gnu -Wl,-rpath,$b/deps/usr/lib/x86_64-linux-gnu -l:libtalloc.so.2 -Wl,-z,noexecstack"
```

新 runner binary：

```
/tmp/xserver-proot-diagnosis-20261030/proot-5.4.0/src/proot
SHA256 a01386f257ac3d18ee42b7e54d7f3796730fb22080b3be242ccdcf51f47f67ff
```

显式环境：`PROOT_NO_SECCOMP=1`、`LD_LIBRARY_PATH=/tmp/xserver-proot-diagnosis-20261030/deps/usr/lib/x86_64-linux-gnu`。App 测试使用明确的新 runner 入口，保留旧入口用于对照。

## 验证结果

独立原生验证记录：远端 `runner-verification.log`，本地 `/tmp/termux-desktop-fixes-20261030/proot-runner-verification.log`。

| 场景 | 结果 |
| --- | --- |
| stock proot、绝对 target `/run` | 原断言稳定复现，退出 1 |
| fixed proot、绝对 target | `/run` |
| fixed proot、相对 target | `relative-target` |
| 独立 rootfs、绝对/相对 target | 正确保留 |
| 独立 rootfs、host root 前缀 | 转成 guest `/tmp/root-target` |
| 非对称 bind、同一 binding 内绝对 target | 转成 `/guestbind/target` |
| 非对称 bind、相对 target | 正确保留 |
| 无效 fd、零长度 buffer、普通文件 | 分别为 `EBADF`、`EINVAL`、`ENOENT` |
| buffer 长度 3 | 返回 3、内容 `/ru` |

模拟器 `emulator-5554` 与远端小米 `XIAOMI_SERIAL` 实际安装版本均为 `5.1.107.101`，binary SHA256 均为：

```
bc3e5416748276374c513eaad1c6cabedc94246fdf2dd58fa6531374e481fa09
```

两台均通过实际 proot 的绝对/相对 target 最小探针。上游 `termux/proot` 的 `v5.1.107.101` tag 指向 `b27d81a68d765f3462e807f4a14e01a03f8e89ea`，其源码已包含空路径 fd 恢复逻辑；此处通过版本号和运行结果核对，未声称二进制可复现构建溯源。

设备取证日志：`/tmp/termux-desktop-fixes-20261030/proot-device-readlinkat-validation.log`。验证后仅清理本轮新增的 `desktop-diagnostics/proot-readlinkat-20261030/` 和 `/data/local/tmp/xserver-proot-readlinkat-empty`，已确认两台均不存在。未替换设备 proot，未修改桌面、用户文件、MultiTouch jar 或安装备份。

完整 15 包恢复结果由 App 子任务的恢复报告记录；上述最小探针仅验证 runner，不替代正式功能的 App UI 验证。
