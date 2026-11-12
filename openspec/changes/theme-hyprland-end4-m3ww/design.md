# 设计

## 调研基线

官网来源：https://github.com/hyprwm/hyprland-website/blob/main/src/routes/home-slices/PreviewRiceSlice.svelte；声明视频/videos/end_4_rice_intro.mp4和Setup by @end_4。作者：https://github.com/end-4/dots-hyprland；当前main使用Quickshell，旧EWW配置在archive分支。研究固定archive提交2433298bddf6b1597b8e10dba67d26891664db11，目录m3ww/.config/。该目录包含eww、hypr、foot、fish、starship.toml。旧方案已停止维护，迁移时不直接执行其全局安装脚本。

已在编译机/workspace/termux-packages查到EWW 0.6.0-2、Foot 1.28.0、Fish 4.9.3、Starship 1.27.0、gtk-layer-shell 0.10.1配方；只证明具备构建入口，不等于当前设备安装或兼容验证通过。EWW上游v0.6.0 Cargo features默认包含x11与wayland。

## 外观与实现

采用EWW GTK3/layer-shell作为宿主配套，保留r7私有D-Bus、清洁GTK环境、独立会话token与服务清理。顶部圆角深色底、蓝紫强调色，中部工作区胶囊，左侧应用入口/任务，右侧时钟、电量与通知入口；小分辨率收起次要模块，触摸目标至少约44逻辑像素。以实际窗口/工作区IPC更新高亮和任务状态，菜单及启动器仍通过现有native/PRoot封装执行。

应用搜索列出当前模式可启动的真实应用。通知避免与已有xfce4-notifyd争夺org.freedesktop.Notifications；先保留服务并统一主题，通知中心仅在真实历史数据接入后启用。音乐控件按真实MPRIS会话动态出现；缺少接口的无线/蓝牙/亮度/主机关机控件不移植。

Foot采用适度透明和一致的蓝紫色板，Fish/Starship仅在本桌面终端的私有配置下启用，不修改用户全局shell。字体和壁纸作为独立资源固定来源、版本和许可；保留必要署名与修改源码，未确认可再分发的官网壁纸不直接打入产品。

Hyprland当前0.57使用Lua，不能复制旧hyprland.conf。将m3ww的间距、圆角、渐变边框和动画转换成当前Lua配置接口；旧hyprctl keyword/dispatch命令通过现有Python适配器转为有效接口，分辨率由真实输出IPC获取，替换xrandr、systemctl、/sys/class/power_supply等接口。

## 显卡效果与性能

当前Android输出是ARGB8888，未启用FP16。圆角、边框、透明和动画按真实能力配置。模糊先以低开销参数探测，保留清晰可读的半透明/实色降级；如出现黑块、闪烁或明显拖慢则关闭该效果，不能虚报渲染能力。实机对比r7基线的空闲资源与窗口交互帧表现，记录设备、分辨率和参数。

## 升级、影响与回滚

所有新外观放入版本化theme目录；系统生成配置可由旧指纹识别并迁移，用户修改过的hyprland.lua、面板和终端配置不覆盖。提供明确的m3ww主题启用/恢复r7选项，使已有桌面能实际应用新版；不可只安装资源而继续使用旧面板。

拟改路径：desktop_hyprland.py、desktop_hyprland_shell.py、desktop_install.sh、desktop_start.sh、LaunchScriptInstaller、DesktopEnvironment/LaunchSpec及主题资源；仅在组件实际变化时更新身份。安装检查包含EWW Wayland、Foot、字体和必要工具；失败清除就绪并保留日志。失败回滚通过切回r7主题及其已验证运行包，避免重装/清数据。

## 流程状态

用户明确“批准，我就要这种花里胡哨的”，需求/设计及披露路径已确认，进入实施。产品文档Required/Pending，HTTP API N/A。仓库Harness工作流、脚本、OpenSpec CLI及目标产品文档缺失，严格门禁Unavailable；不伪称生成器、严格门禁或设备主题验收已通过。
