# 设计

优先复用编译机现存源码、交叉工具链和sysroot。锁定上游 v0.3.0 标签及提交，在新目录构建，不覆盖现有版本。核对已有源码补丁，不能把较新 Box64 二进制改版本后充当 0.3.0。

Native 使用最终 Termux GLIBC 加载器 /data/data/com.termux/files/usr/glibc/lib/ld-linux-aarch64.so.1 和运行库搜索路径，在链接阶段嵌入，避免事后扩长PT_INTERP造成ELF重叠。PRoot 使用标准 /lib/ld-linux-aarch64.so.1 与容器glibc库路径，两者均输出AArch64，不因构建机是x86_64混淆目标。按现有包结构打包，Native带既有x86_64配套库，PRoot参照现有运行包布局。

使用Release构建；记录源码、选项、编译器、解释器、NEEDED和glibc版本需求。检查ELF加载段、运行版本；有现存qemu/sysroot时通过目标环境加载器验证，不能将模拟运行当作Android设备验证。按 glibc_box64_0.3.0.tar.xz 和 rootfs_box64_0.3.0.tar.xz 命名并提供下载。

回滚仅删除本次独立构建目录和本地新产物；不触及原构建目录或既有发布。Harness引导文件、strict verifier与OpenSpec CLI缺失时如实记录不可用。
