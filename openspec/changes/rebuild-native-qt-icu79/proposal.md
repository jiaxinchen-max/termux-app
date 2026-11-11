# 按当前 ICU ABI 重编 Native Qt

Native KWin Plasma 启动失败：定制 Qt 6.12.0 的动态依赖指向 ICU 78，而当前 Termux 仓库提供 ICU 79.1。包元数据只声明无版本约束的 libicu，导致安装成功但程序无法加载。

用户提供已启动的指定 Termux 编译机，用于继续此前讨论的重编工作。本阶段准备可审查的候选构建：保留已验证源码和定制补丁，针对 ICU 79 重编，检查 ELF 依赖及包依赖约束。不得通过伪造 SONAME、降级设备全局 ICU 或更换渲染模式处理。

用户已完成候选编译，并明确授权更新原组件仓库、同步 App 下载清单及编译 APK。保持现有组件版本、文件名和发布位置；发布前核对真实依赖与包内容。设备安装、Git 提交推送及最终产品验收不包含在本轮授权中。

api_docs 为 N/A：不改变 HTTP 契约。product_docs 为 Required：涉及 Native 桌面与系统依赖兼容性，目标为 docs/product/desktops.md；用户界面验收前一致性保持 Pending。
