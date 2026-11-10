# Proton 11 控制面板枚举修复

回移 Wine 官方提交 `70ed59e3f4570e61aea2f36a4d18b9c6eaa0f922`，只忽略可选 AUTOCENTER 属性失败；创建、数据格式、协作级别错误仍拒绝设备。源补丁为 `ignore-unsupported-autocenter.patch`。

`build-panel.py <原构建目录> <独立输出绝对路径> <x86_64/i386...>` 读取原 Makefile 的编译、链接命令，复用未改模块的对象和导入库，在独立目录构建旧版对照及修补版，不修改原源码或构建输出。执行前必须恢复原工具链环境。

本次 Native 沿用 GCC 12.4（原 `build_proton110/build64`、`build32`）；PRoot 沿用 Debian 13 buildroot 的 LLVM MinGW 20260826（原 `/work/build-proton-full3`）。产物是 Windows PE，不更换两种后端的 Unix 加载器或库。两种架构旧版重编的 `.text`、`.rdata`、`.rsrc` 均与线上包一致，确认编译来源与配置匹配。

`test-enumeration.py <旧源文件> <新源文件>` 提取真实枚举回调，以可控的 HRESULT 覆盖全成功、不支持 AUTOCENTER、三个必需步骤分别失败，并使用 ASan/UBSan 执行。该测试不代替 Android 实机输入验证。

`repack-panel.py <原包> <输出包> <i386 joy.cpl> <x86_64 joy.cpl>` 仅替换两份面板，完成后重新读取压缩包，逐成员比较其他数据、目录与链接元数据。保留组件 ID、版本号、URL 和现有 prefix；已安装组件由用户显式重装，不自动升级。

原包同时保留为回滚副本。产物清单、构建命令、反汇编、包差异和人工验收状态记录于本次运行证据；摘要仅用于构建溯源，不增加运行时校验。
