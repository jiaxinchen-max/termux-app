# 设计

将box64-proot-v0.4.4.tar.xz中的glibc/bin/box64与box64-binaries.tar.xz中的glibc/lib/x86_64-linux-gnu运行库组合，不加入旧Box64、历史嵌套归档或macOS元数据。包装工具只接受固定白名单7库，校验ELF架构：Box64为AArch64，库为x86-64；记录源归档、文件清单和摘要作为构建证据，不新增App运行时签名/摘要拒绝逻辑。新包沿用文件名、发布到新版本标签，源资产保留。

TermuxBoxRepository已有版本隔离安装会将glibc下全部内容存至glibc/components/<id>，无需改成共享覆盖。resolve_native_box64将对应组件lib/x86_64-linux-gnu加入BOX64_LD_LIBRARY_PATH前端，保留Wine既有搜索目录。每次解析按本次配置计算路径，不串入其他Box64版本或通过默认版本补洞；独立维护选择库目录，与自定义版本统一路径规则。版本隔离适用于0.2.7及0.4.4。

目录glibc_box64_0.4.4版本从1升2，URL使用新release标签box64-proot-v0.4.4-v2及原资产文件名。先生成可复现包并据此更新大小/摘要，发布前不声称网络链接可安装。对照真实ELF依赖核对libgcc只需libc.so.6，既有Box64桥接其余系统库。其他库仅保留已有Native配套集合，不扩充LLVM、设备插件或新RootFS。

验证真实源包输出、7库架构与字节保留、相同输入的确定输出；Java真实安装路径及版本互不覆盖；Shell使用真实选择函数验证所选库路径、不同配置不互串及缺失版本仍失败。完成针对性回归并编译普通arm64 debug APK，APK固定名称。设备启动验收由用户执行，不后台运行Wine。

回滚代码局部差异及目录版本；保留旧发布资产，不迁移设备数据。Harness引导、严格脚本与OpenSpec CLI缺失，记录Unavailable，不宣称严格门禁通过。
