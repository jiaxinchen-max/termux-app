# 设计

## 已核对的现状与上游
当前native固定gnome-shell_48.7-1、mutter_48.1-24，Android Wayland后端使用System EGL与60Hz；PRoot Ubuntu noble为GNOME46，不能直接复用其扩展与Shell主题。
2026-11-12读取Ubuntu Dock ubuntu-dock分支metadata，包含48；候选提交da735cf14680bc38d643c26db5e445241d9dc3d7。AppIndicator master metadata包含48，候选提交fbc758a467d816ad5d6e9a81148ee7b6b7ba1287，其UUID为appindicatorsupport@rgcjonas.gmail.com，与Ubuntu deb重命名UUID不同，不混用。来源分别为https://github.com/micheleg/dash-to-dock/tree/ubuntu-dock 和 https://github.com/ubuntu/gnome-shell-extension-appindicator 。
DING当前master仅声明50/51；选用extensions.gnome.org的GNOME48映射发布85（version_tag=72024），已下载检查metadata包含48，摘要429171dda75a6d449650679c77370fb7edd17b9b183d68e54ab6892ab478a3f3。其应用使用Gtk3和GnomeDesktop3，并存在GdkX11相关代码，必须检查Android原生Wayland路径的条件分支与GI依赖，不能仅复制脚本后宣称可用。缺依赖时在实施中编译原生依赖或对本版本进行有记录的Wayland适配；未解决不能交付“桌面图标可用”的结论。Yaru从https://github.com/ubuntu/yaru 选取与GNOME48匹配的固定源码版本构建，最终版本与摘要在实现审查前锁定；不使用移动分支运行时下载。研究响应、metadata与DING压缩包保存在工作区artifacts/native-ubuntu-gnome-planning/。

## 原生外观配置
UBUNTU_GNOME在native下变为可创建，原生共五种候选（已有实例按当前规则隐藏），PRoot仍五种。原生卡片说明“Ubuntu风格 · 原生GNOME”，容器卡片说明Ubuntu24.04底包，避免能力误导。所有支持判断按runMode和de组合处理，不能把容器专用ubuntu:noble校验套在native。
原生profile显式区分native-ubuntu与现有gnome和容器ubuntu；向启动器传递固定profile，完整验证安装资产和版本。使用适配后的Ubuntu Shell mode及Yaru资源，避免GTK主题已变但Shell仍Adwaita；按原生PREFIX构建资源路径、schema、gjs路径和D-Bus服务。GSettings按会话HOME和dconf隔离；首次初始化Yaru、Ubuntu壁纸、左侧Dock与收藏应用、桌面图标，后续保留用户外观修改。需要设置服务时以原生可执行程序受控启动，不引入systemd/GDM依赖。
原生GL仍System EGL，guest为NONE，后端android/wayland，刷新60Hz；普通GNOME现有约束保持。Ubuntu的容器SYSTEM/LLVMPIPE契约保留。Ubuntu原生暂不开放USB和备份恢复，安装前和启动前一致拒绝非法组合。无旧格式兼容或迁移。

## 安装、启动和就绪
新组件使用可复现固定来源和SHA256，保留各上游许可证及壁纸许可，构建时排除无关Ubuntu发行版二进制。先检查现有Termux软件包可提供的Gtk3、GnomeDesktop3、GJS typelibs和应用，再构建缺项；最终产物清单、依赖、版本与摘要必须可审查。安装失败不写成功marker，重装可修复缺失资产。
扩展、主题和session mode可共用只读PREFIX资产；用户HOME、dconf与runtime必须分离，不覆盖普通GNOME配置。启动前验证mode、主题资源、扩展、schema、可执行文件和依赖；就绪需确认Shell总线、Wayland socket、Dock/托盘/DING扩展Active及DING实际进程/总线，无静默禁用必选扩展或自动回退。原生应用需验证Wayland启动与D-Bus激活，终端/编辑器优先选可运行的Termux原生应用，不照抄容器命令。停止、切换和异常退出仍只清理会话拥有的进程。

## 受保护路径、影响及回滚
来源为/home/user/workspace/AGENTS.md的人类检查点；机器policy id不可用。拟修改DesktopDe、DesktopCreationCatalog、DesktopRendererPolicy、DesktopLaunchSpec、DesktopEnvironment、DesktopTasks、必要DesktopDistro校验、创建与详情展示文案、LaunchScriptInstaller，以及desktop_rendering.sh、desktop_install.sh、desktop_start.sh、desktop_stop.sh、desktop_gnome_launch.py、新增native profile helper和相关测试；归档/USB仅在需要补齐现有拒绝行为时修改。组件构建脚本拟放本仓库termux-ui/scripts下新增专用目录，外部编译机与组件仓库仅先只读调研，发布前提供具体文件/摘要与目标。
安装和启动链路属高风险：影响新native卡片、原生依赖和进程管理。实施前保存当前r10/r11未提交工作树的源文件快照与摘要；回滚仅撤销本变更新增/修改部分，保留r10/r11及七个dirty原生子模块，不git reset、不删除用户目录。设备回滚需单独环境授权，不以源码回滚宣称已恢复已部署设备。外部发布不覆盖旧组件资产。

## 验证与完成标准
自动测试覆盖native与proot目录、原生SYSTEM/NONE与60Hz、非法PRoot/native参数、native不能触发noble apt、缺主题/扩展/依赖拒绝、安装失败marker、扩展报错、配置隔离、重启保留设置、受控停止和普通GNOME/容器Ubuntu回归。GNOME涉及全局进程夹具单独运行，避免已知测试干扰。
检查原生依赖与ELF架构、资源路径、资产摘要、许可证，构建ARM64 APK并验签与逐资产对比。真实设备需要截图和日志证明Yaru、左侧Dock/程序入口/窗口切换、状态区/托盘、图标/文件打开、原生应用、输入和停止重启；无支持的GPU设备时如实记未验证。Shell进程存活或fixture通过不能算视觉和GPU验收。最终用户接受后维护产品文档。

实施确认：用户已明确“批准”，覆盖详细规划及受保护路径。Yaru锁定25.04.2提交0323aa049c8bfa7a117fdc8c206313a6c7d10c11；上游changelog明确合并GNOME48。DING85仅修改Termux gjs/kill路径，GTK3/GnomeDesktop3使用原生仓库依赖；加入完整GPLv3文本。只读主题/扩展组件随APK内置，避免依赖外部发布，压缩包采用.bin后缀以避免Android打包器自动解压改名，安装依SHA256校验内容。构建脚本固定所有来源摘要并产出逐文件manifest；内部分发hardlink仅指向包内先出现的常规文件，以减少重复图标的安装空间。最终bundle摘要：0be2d58e3a4671e77848984919b5cfa066ec473a5cc1ff8977749eb80df5b4f8。

## 分发调整（取代前述APK内置方案）
用户已明确批准外置组件及组件仓库发布，授权覆盖安装链路、资源安装清单、回归和APK重编译。固定组件名native-ubuntu-gnome-48-r1.bin、SHA256 0be2d58e3a4671e77848984919b5cfa066ec473a5cc1ff8977749eb80df5b4f8；发布至https://github.com/jiaxinchen-max/termux-app-packages/releases/tag/native-ubuntu-gnome-48-r1，附许可/来源清单与摘要。APK不含此资源包，创建时复用curl下载能力，固定HTTPS URL，先校验缓存，失效则重新下载到临时文件，完整SHA256通过后原子替换缓存，再调用原有严格安装器。下载/摘要失败不能安装或写成功标记，清理临时文件并明确报错；有效缓存可离线复用。桌面启动不新增联网依赖。回滚仅恢复本轮文件快照和原内置方案，不覆盖旧发布、不修改其他桌面或七个dirty子模块。验证覆盖首次下载、离线缓存、损坏缓存修复、下载中断/摘要不匹配拒绝、APK不含组件、签名与体积。无新增HTTP API契约，产品文档仍待最终接受。

## Android解包实现修正
实际设备Python没有os.link，不能依赖Linux宿主机API。已经验证必须指向先前常规文件的内部hardlink，在安装时通过shutil.copyfile复制为独立常规文件；安装后每个文件仍按原manifest SHA256校验，使用临时文件原子替换，失败不写marker。代价是安装后不再共享重复图标inode，磁盘用量增加；压缩组件和APK大小不变，无新增依赖、迁移、软件渲染回退或外部发布。验证包括无os.link接口的最小复现、真实组件摘要与修复测试、设备独立临时PREFIX内完整解包与依赖探针。设备正式PREFIX与APK不自动部署。

## 桌面退出缺陷修复
实际日志显示GNOME已使用System EGL运行，但启动器查询org.gnome.Shell.Extensions代理服务（core策略未启用）超时30秒后关闭会话；DING85在构造时遍历/proc/asound/cmdline被拒绝，扩展初始化失败。修复已批准的扩展/就绪/生命周期范围：就绪查询直接调用org.gnome.Shell、/org/gnome/Shell的同名扩展接口，不启用额外偏好设置代理服务；保留Active状态、错误状态和DING总线检查。原生DING去除启动时全局/proc扫描清理，由现有会话进程组拥有和清理DING子进程，不能跨桌面扫描/杀进程。构建固定新组件native-ubuntu-gnome-48-r2，保留上游来源/许可、补丁与摘要清单，不覆盖r1。增加拒绝错误D-Bus地址的进程夹具，验证原生补丁不触发/proc扫描并保留实际DING功能。回滚使用pre-session-exit-fix.tar.gz恢复本轮文件，旧组件不覆盖；安装目录在更新后需重新安装r2，不添加旧数据迁移。

## 基础应用补齐（用户已确认）
用户在确认缺少设置中心后明确要求“补上”，本轮沿用既有安装与会话高风险路径授权，补齐真正的原生GNOME Control Center、计算器、时钟、图片查看器。设置中心基于GNOME48源码适配Bionic，保留上游界面及实际可用的外观、工作区、通知、键盘、搜索、声音及辅助功能面板；依赖Android系统权限或缺少后台服务的网络、电源、用户管理等面板不展示，不放假入口。固定源码摘要及补丁、许可、原生包依赖可审查，不能以Android设置替代。实际验证设置窗口、修改与重启持久性及基础应用窗口。新增包独立按需获取，不内置大型资源；发布具体资产前遵守现有外部发布授权范围。无HTTP契约，product_docs仍Required/Pending。回滚仅恢复pre-apps-container-fix.tar.gz及本轮新增配方，不覆盖既有未提交修改、不迁移旧数据。

设备窗口验证发现标准gnome-clocks51.0仍链接ICU78而宿主为79.1，不能将apt成功视为可运行。沿用上述真实基础应用与ICU适配范围，基于同一固定上游源码重编时钟并显式依赖libicu；以独立新组件版本发布，保留旧发布。增加原生应用可执行探针，拒绝缺少ICU等运行时链接依赖；补故障回归后重编正常APK。
