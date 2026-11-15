# 验证记录

## 已有实机证据
当前容器 import-74064c81-5b1d-4c37-9721-30e687b2d77b：root 下 VLC 3.0.23-1 退出 1，提示 VLC is not supposed to be run as root。受控终端通过 runuser -u nobody -- sh -c 'id; vlc --version' 返回非零 UID 和 VLC 版本，命令退出 0。此项仅定位身份阻断，不代表图形/音频验证成功。

## 源码检查
DesktopArchiveMetadata.encode 保存 name；DesktopArchiveEngine.importArchive 使用 old.getName()；DesktopArchiveTasks 发布清单前尚未按现存名称去重。desktop_container_session.sh 中系统服务与用户会话由同一 root 流程启动；GNOME 直接 proot 路径及 HOME 显式使用 /root。

## 当前状态
仅规划，尚未修改业务代码、设备账户、备份或容器；未构建 APK。未执行严格 Harness gate（脚本缺失）。
