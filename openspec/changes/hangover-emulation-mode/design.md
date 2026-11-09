# 设计

官方文档与当前 prefix 资产描述均表明：HODLL 控制 32 位模拟器，WowBox64 为 wowbox64.dll，FEX 为 libwow64fex.dll；64 位使用 HODLL64=libarm64ecfex.dll。保存官方文档快照在 artifacts/hangover-emulation-mode。

使用逐游戏环境键 GAMES_HANGOVER_EMULATOR，取值 wowbox64 或 fex，缺省明确 WowBox64。小型枚举统一选项、DLL 名称与非法值拒绝。仅 PRoot Hangover 游戏展示此项；Native、普通 Wine/Proton 不展示、不改变其启动方式。更换 Wine 后保留配置键但仅 Hangover 使用。

在 start_rootfs_game.sh 增加启动专用配置函数，在解析翻译类型后调用，设置 HODLL 与 HODLL64，并检查实际使用 DLL 非空存在；不调用 wineboot 或创建模板，不改变 prefix 身份或准备记录。共享 prefix helper 保持原样，避免改变准备配方身份。运行时配置验证拒绝非法模式。错误由既有失败事件上报。DLL 检查是必要运行文件存在检查，不是摘要/签名校验。

入口显示当前模式与适用位数。编辑草稿、Discard、Save 和重建状态复用现有 profile 环境机制。已有保存流程仍可显示准备任务，但模式不产生新 prefix 资产或重建动作。

风险为环境参数被忽略、误用于独立 Box64、误改 64 位模拟器；通过界面选择/保存/取消/重建、两游戏隔离与实际启动脚本捕获环境验证。失败用例覆盖非法模式与缺失 DLL；确认零回退。回滚只恢复本次快照和新增文件，保留原有脏工作树。无新受保护路径、Git 或部署操作。
