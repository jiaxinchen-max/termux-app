# 固定桌面音频服务生命周期

Native 和 PRoot 均因 Host PulseAudio start failed / exit:70 退出。现启动路径直接 --start，无法正确复用跨包更新或测试进程留下的共享服务。追溯到 52ccd543：原 start_desktop_audio 的复用流程被改名为 prepare_desktop_audio，新日常入口只执行 --start。最近卡片尺寸提交并未修改启动逻辑，但上轮 run-as 测试留下的共享服务触发此缺陷。用户明确要求每次运行桌面先检测、能复用则复用，否则停止旧服务再启动；音频属于运行时依赖服务，不属于一次性环境初始化。

本次统一安装准备、保存恢复与日常运行的音频服务管理：通过真实 loopback 端点和 Android sink 判断可用性；健康时不启动、不杀进程；不可用时仅停止本 UID 且确认命令身份的 PulseAudio，等待退出后单次启动并验证。所有操作有期限，失败直接退出，不修改桌面或容器配置、不安装组件、不重试桌面。

用户已在本轮直接确认上述处理逻辑，沿用结束后推送进度授权。无 HTTP 契约，api_docs=N/A；product_docs=Required，目标 docs/product/desktop-runtime.md，验收前 Pending。缺少仓库 Harness 指令、verify.sh 与 OpenSpec CLI，如实记录无法运行严格门禁。
