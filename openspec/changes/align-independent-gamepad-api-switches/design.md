# 设计

## 配置与交互

将两个接口启用布尔值与既有 inputProfileId（映射和触控布局）分开存储，贯穿 RuntimeProfile、仓库读写、编辑页、LaunchSpec、运行配置及 LorieViewRuntimeController。默认均启用，表示现有同时提供两类接口的行为；这不是旧格式迁移或兼容分支。重启桥接与下一次启动必须应用同一份快照，禁止沿用上一游戏开关。

Advanced 显示两个独立开关和 DirectInput 映射。触控布局关闭不等于关闭所有接口，外接手柄仍受接口开关控制。两项接口均关时保留布局选择和键鼠操作；保存后的下一次启动生效，不要求热切换。

## 协议与 DLL

沿用当前单手柄、64 字节 UDP 报文与设备信息布局，不启用现有另一套多槽协议。对齐 CMod 对 GET_GAMEPAD 回包偏移 5 的标志定义：Standard 映射 0x01，XInput 映射 0x02，启用 XInput 0x04，启用 DirectInput 0x08。映射位二选一，接口位独立组合；默认两者开启时分别为 0x0d 或 0x0e。

当前旧 DLL 把该字节当作 mapper=0/1，XInput 更未检查它，因此必须与应用同步重编并完整替换当前载荷，不能只改变 Java 回包。DirectInput DLL 在枚举及创建设备路径检查 0x08，再从映射位选择轴键布局；XInput DLL 检查 0x04，关闭时报告设备未连接。非手柄的 DirectInput 设备处理保持原样。

桥接保留 GET_GAMEPAD 请求的 isXInput 来源，按端口记录接口及通知订阅；关闭的接口明确回复无设备，不注册推送。GET_GAMEPAD_STATE 及异步推送均校验接口来源和当前启用状态，不能仅过滤首次枚举后仍发送状态；断开或重新建立桥接清空旧订阅。DLL 接收逻辑同时检查启用位，避免仅在 UI 隐藏设备。

## 架构、构建与交付

普通 Wine 当前载荷源为 Wine 9.15，重编 x86_64/i386 的八个 dinput/xinput DLL；Hangover 使用其 Wine 11.16 对应源和现有移植补丁，重编 aarch64/i386 对应 DLL。记录源提交、补丁、编译选项和 PE machine/导入信息；保留 Hangover 所需节布局与 builtin 标记。不混淆 Windows PE、Linux glibc ELF 或 Android bionic 库。

编译机 /home/builder/wine-glibc-build 与 /home/builder/hangover-11.16-idap 有基础源码和工具链；现有源码中未发现带 UDP 控制器的 gamepad.c，因此构建使用单独任务目录及固定版本，不 reset/clean 现有源码和构建目录。保留本次可复现补丁和构建步骤。

原子重打 termux-x11/src/main/assets/wine.tar.gz，检查原有各架构清单全部保留且只替换目标 DLL。沿用 controller_runtime.sh 已有对应架构与 builtin/native 部署策略，无需重建 prefix 或下载新 prefix。若载荷缺失或部署失败，明确终止本次带控制器的启动，不降级成未应用开关的运行。此规则仅涉及本次控制器部署路径，不扩大改造其他启动分支。

仅构建正常非 test-only、原名 termux-app_apt-android-7-debug_arm64-v8a.apk，由用户安装和验证；不操作模拟器。无须新增组件候选、资产版本或自动重装。当前计划不含 Git 操作和外部资产发布。

## 风险、回滚与验证

主要风险是应用和 DLL 协议错配、遗漏某一 DLL 变体/架构、订阅状态残留、设置在编辑或启动快照中丢失。验证覆盖四种接口组合乘以两种映射，以及保存重读、触控布局不变、跨游戏与桥接重启、禁用接口无枚举和无推送、DirectInput 键鼠保持可用。原有 joy.cpl AUTOCENTER 修复必须保留。

回滚必须同时恢复本次应用协议和载荷，不允许只回滚一端；不回滚其他未提交改动，不触碰游戏与 prefix 数据。二进制资产属于本次显式列明的敏感修改范围，待方案确认后才执行；产出差异清单与可恢复旧载荷。用户设备上分别验证 Native Wine9.3、Native Proton、PRoot Proton、Hangover 的接口枚举及按键状态，静态检查与单元测试不替代设备验收。
