# fwupd 查询收尾真实插件回归

只在用户指定编译机 `BUILD_HOST:7022` 执行；工作目录 `/tmp/fwupd-state-test-20261030`。没有访问设备、修改生产库或操作正式 App。

实际测试对象是 DiscoverBackendsFactory 装载的 arm64 fwupd 插件、libfwupd、libDiscoverCommon 和 ResultsStream。独立宿主 Python/Gio 进程导出 `org.freedesktop.fwupd`，使用本目录私有 dbus-daemon socket，同时设置测试进程的 system/session bus 地址。没有连接宿主真实 system bus。服务独立进程，避免 fwupd Sources 构造器同步 GetRemotes 导致同线程死锁。

测试运行器不伪装 root，使 D-Bus EXTERNAL UID 与宿主 peer credentials 一致；该变化只在自有夹具 `run-arm64-user`，没有修改原生产/编译 runner。编译仍用本目录 `run-arm64`、指定编译机已有 arm64 rootfs/GCC；本地未编译。

## 对照与判据

`baseline` 必须复现旧插件的既定错误：GetDevices 异步错误后原始 passive error 保留、fetching progress=42、初始化信号不发、已建立的 ResultsStream 在观察窗口仍存活。诊断成功退出 0 表示已复现旧缺口，不表示旧插件功能通过。

`fixed` 必须对每一轮验证：服务回复前 progress=42 且搜索流仍 pending；回复后 pending 流销毁、progress=100、initialized 恰发一次、progress change 通知发出、后续新搜索也结束。一般真实错误仍须 passiveMessage 可见；成功空列表和非空列表不能产生伪错误。

两组初始模式均显式指定：`error` 返回 org.freedesktop.fwupd.NotFound；`no-devices` 返回 org.freedesktop.fwupd.NothingToDo / No detected devices。每组候选继续验证 empty、nonempty、no-devices、error 五轮（含重复刷新）。非空夹具包含一个有效但不受支持（Flags=0）的设备，覆盖非空结果查询收尾，不声称覆盖真实固件更新/安装业务。

初次 no-devices 的实际旧插件和最终候选均记录 `Fwupd Error 10 No detected devices`；修复后保留该错误但正常结束等待。后续错误过滤仍遵循原 handleError 规则，没有改错误语义。

## 实际命令

```sh
/tmp/fwupd-state-test-20261030/run-arm64 cmake -S /tmp/fwupd-state-test-20261030 -B /tmp/fwupd-state-test-20261030/build
/tmp/fwupd-state-test-20261030/run-arm64 cmake --build /tmp/fwupd-state-test-20261030/build --parallel 2
python3 /tmp/fwupd-state-test-20261030/run-test.py /tmp/fwupd-state-test-20261030/baseline baseline error
python3 /tmp/fwupd-state-test-20261030/run-test.py /tmp/fwupd-state-test-20261030/candidate fixed error
python3 /tmp/fwupd-state-test-20261030/run-test.py /tmp/fwupd-state-test-20261030/baseline baseline no-devices
python3 /tmp/fwupd-state-test-20261030/run-test.py /tmp/fwupd-state-test-20261030/candidate fixed no-devices
```

旧插件来自 fix2 deb，SHA256 `457e6ac6b2dc90d18603ca64ded83880048dfdca286b35647d58c8c2b97e3591`。

候选来自最终 fix3 deb，SHA256 `2745d49d8b4b4932fe437c0fee1f4dd782de9517caa1a9a104f7aca90c94f5d6`。`plugin-proof.json` 已验证测试副本与最终 deb 解包插件逐字节相同。

结果见 `result-baseline-error.log`、`result-fixed-error.log`、`result-baseline-no-devices.log`、`result-fixed-no-devices.log`；服务请求日志 `server-*.log` 证明实际 D-Bus 往返。执行器同时检查退出码和预期结果标记，未将 proot/qemu 的异常退出当作通过。

测试确认 fwupd 查询生命周期修复，不代表 PackageKit 池查询或正式 App UI 已验收。
