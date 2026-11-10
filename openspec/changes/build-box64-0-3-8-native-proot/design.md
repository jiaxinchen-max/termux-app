# 设计

锁定上游v0.3.8提交81c56d7155cdd7a4c49173a2fe4d7bdd87698683。在现存Debian构建环境中新建独立目录，以AArch64 glibc工具链串行构建Release并剥离调试信息。Native解释器使用Termux glibc绝对路径；PRoot使用/lib/ld-linux-aarch64.so.1。分别复用现有目标的x86_64配套库并核对架构、符号链接和包内容。

验证版本、静态系统调用和动态libc/pthread程序。新建两个独立Release，命名glibc_box64_0.3.8和rootfs_box64_0.3.8，不覆盖现有版本；回滚可撤下本次新增Release。不安装设备，不声称已通过Wine游戏测试。
