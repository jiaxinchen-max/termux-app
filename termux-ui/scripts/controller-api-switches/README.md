# 独立手柄接口开关的配套 DLL

APK 的 `wine.tar.gz` 与 `GamepadApiPolicy` 必须一起更新。保留单手柄 64 字节协议，GET_GAMEPAD 回包偏移 5 使用映射 Standard=0x01、XInput=0x02、启用 XInput=0x04、启用 DirectInput=0x08。默认同时启用两个接口；这与 DirectInput 轴键映射及 DLL 加载顺序分别保存。

## 固定源码与构建

- 普通 Wine：wine-mirror/wine 的 wine-9.15，GitHub 源码归档 SHA256 为 `735c34c446fe00439d22f42eca9dec91acc0aed3b5164260fc726e03ad8bc60e`。`gamepad-wine-9.15.patch` 来自原载荷的 jiaxinchen-max/Wine-Builds 手柄补丁，保留其版权声明。
- Hangover：Wine 11.16，提交 `3ea3a14941b6a122b892c84141ce9662c008081e`，基础补丁是相邻 `../hangover-controller-dlls/gamepad-wine-11.16.patch`。
- 编译工具：已有 buildroot 内的 llvm-mingw 20260826 ucrt，目标都是 Windows PE DLL，不是 glibc/bionic ELF。构建不修改既有源码和运行时目录。

在编译机的 Hangover buildroot 中创建新的 `/work/controller-api-switches`（重复构建可通过 TASK_DIR 指定另一个目录），将两个基础补丁、`apply-api-flags.py`、`build-dlls.sh` 放到 `inputs/`，并将上述源码归档放到任务目录的 `wine-9.15.tar.gz`。代理建议在 buildroot 外使用 `socks5h://172.17.0.1:7897`，本次比直接 SSH 大文件上传快。

```sh
sudo chroot /home/builder/hangover-11.16-idap/buildroot \
  /bin/sh /work/controller-api-switches/inputs/build-dlls.sh
```

`apply-api-flags.py` 在基础补丁上加入两个接口判断、映射位解析，以及 DirectInput 创建设备时必须处理枚举失败的检查。脚本校验待替换语句数量，源码不匹配就失败，不套用旧 DLL。产出 `out/9.15/{x86_64,i386}-windows/` 和 `out/11.16/{aarch64,i386}-windows/`，每种架构八个 DLL。

## 验证与打包

`test-api-clients.py <patched-source> <output>` 提取真实的 DirectInput 请求函数、XInput 接收分支和 DirectInput 创建函数，以可控响应覆盖 16 种响应组合和 3 种创建场景。Windows/socket 入口使用桩；这不替代 Android/Wine 设备验收。启用 ASan/UBSan，接收函数因原线格式的偏移 1 整数读法单独关闭 alignment 检查。

打包需同时核对全部 32 个 DLL 的 PE machine、builtin 标记、接收缓冲区标志检查；Hangover aarch64 保留 64 KiB 节对齐与 `.hexpthk`、`.a64xrm`。八个库分别为 dinput、dinput8、xinput1_1、xinput1_2、xinput1_3、xinput1_4、xinput9_1_0、xinputuap。保留原归档成员清单、四种架构目录以及已有运行时的 builtin/native 部署方式。

普通 DLL 放入 `wine/`，Hangover DLL 放入 `wine-hangover/`；保存旧归档作为配套回滚备份，生成确定性 tar/gzip 后原子替换 APK 内资产。运行 `test-controller-runtime.sh`、Native/PRoot 启动集成测试、Java 测试及正常 APK 构建。APK 名称保持 `termux-app_apt-android-7-debug_arm64-v8a.apk`，不得带 testOnly。

仅更改 DLL 和开关不需要重建 prefix、更新组件版本或重新发布 Wine/RootFS 包。回滚应同时恢复应用协议和 DLL 载荷，保留其他改动；不回滚 joy.cpl 的 AUTOCENTER 修复。
