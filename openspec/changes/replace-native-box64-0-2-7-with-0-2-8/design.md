# 设计

## 构建和资产
固定上游v0.2.8提交8545d0505f274554561ca30e1839027dd7be783a，沿用已有GCC14.2 AArch64 glibc交叉环境，Release动态翻译构建并strip。解释器必须为/data/data/com.termux/files/usr/glibc/lib/ld-linux-aarch64.so.1，RUNPATH为同目录，LOAD对齐64KiB。不使用PRoot解释器。打包glibc/bin/box64和现有Native支持库，独立发布glibc_box64_0.2.8.tar.xz，目录记录真实大小和来源。

## App接入
替换目录默认基础项ID、名称、版本和下载元数据，组件修订号从1开始；NativeBox64Versions、安装探测、停止任务匹配及shell运行器统一识别0.2.8，默认与显式选择均指向独立目录usr/glibc/components/glibc_box64_0.2.8。清理相关活动资源和测试中的旧版引用。原有PRoot配置不变。不新增摘要、签名或旧数据迁移。

## 风险、回滚和验证
关键风险是默认项与安装/启动不一致、误用PRoot链接器，以及缺少x86_64配套库。构建后检查ELF和包内容，QEMU下执行版本、静态系统调用及动态libc/pthread验证；运行现有默认、显式选择、卸载重装、失败不回退、任务强杀和目录测试并构建正常APK。回滚只撤销本次差异并恢复原目录项，不操作用户数据。保护规则文件缺失，启动脚本作为运行关键路径记录检查。用户本条要求授权本次构建替换及原组件仓库发布，未授权Git操作和设备操作。
