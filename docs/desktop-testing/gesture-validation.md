# 可重复的 App 界面手势验证

`validate-ui.py` 只提供 UI 点击、滑动、多指注入、按键、截图与新鲜 UI 树定位。桌面安装、启动、停止、Renderer/触控模式/方向选择都必须通过 App 界面完成。脚本不提供任意 shell 步骤、配置文件改写或桌面命令启动入口。

运行环境：Python 3；需要截图比对时安装 Pillow。系统 Python 没有 Pillow 时命令会报错；不会换用别的比对方法。本机不编译 Java/Android 产物。

## 在指定编译机构建 mt.jar

显式提供 javac、d8、android.jar 与 min-api，脚本不查找或切换编译器：

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
bash build-multitouch.sh \
  /usr/lib/jvm/java-17-openjdk-amd64/bin/javac \
  /home/builder/desktop-fixes-20261030/android-sdk/build-tools/34.0.0/d8 \
  /home/builder/desktop-fixes-20261030/android-sdk/platforms/android-34/android.jar \
  /home/builder/desktop-fixes-20261030/artifacts/multitouch-tool 24
```

产出 `mt.jar` 和记录源码/产物 SHA256、工具路径、min-api 的 `mt.jar.json`。本轮远端构建通过：

- Java 源码 SHA256：`c26c606d579c1a048a7eb52b44a7e6f03f44330ce29ae884457a5ebb9852c97a`
- mt.jar SHA256：`77334e9f5ae344df36f9131b0416151f8e832e4ec0fb99dada116ef753ec1751`

新 Java 参数契约必须指定 `--input-manager=global` 或 `--input-manager=legacy`。Android 15 本轮设备使用 `global`；所选 API 不可用或事件注入被拒绝时退出，禁止尝试另一个 API。成功注入后等待平台完成事件；失败只取消本工具未结束的触摸序列，不切换输入方式。

## 显式安装到指定设备

```sh
python3 validate-ui.py --adb-server local --serial emulator-5554 install --jar /absolute/path/mt.jar
python3 validate-ui.py --adb-server remote --adb-host BUILD_HOST --adb-port 8037 --serial DEVICE_SERIAL install --jar /absolute/path/mt.jar
```

默认安装到 `/data/local/tmp/desktop-validation-mt.jar`，保留原先 `/data/local/tmp/mt.jar`。可用 `--device-jar` 显式改变目标。推送后检查设备 SHA256；连接、推送、校验失败均退出，不切换设备、ADB server 或传输方式。

直接调用示例：

```sh
adb -s emulator-5554 shell 'CLASSPATH=/data/local/tmp/desktop-validation-mt.jar app_process / MultiTouch --input-manager=global tap2 1250 500 1400 500 80'
```

## 双屏设备的显式截图目标

全局参数 `--display-id` 指定 SurfaceFlinger 的十进制物理屏幕 ID，仅用于 `screencap -d`。它与 Android 输入的逻辑 display ID 不同；当前输入仍发往已知的逻辑 display 0，不随截图目标切换。

小米 Mi 11 Ultra 前后双屏的主屏示例：

```sh
python3 validate-ui.py --adb-server remote --adb-host BUILD_HOST --adb-port 8037 \
  --serial DEVICE_SERIAL --display-id 4630946736638489730 run \
  --plan plans/direct-touch.example.json --output /absolute/path/new-phone-run --target-rotation 0
```

未指定 `--display-id` 时保留原单屏 `screencap -p` 行为，不自动选择屏幕。若 stdout 含 Warning 前缀导致 PNG 签名不正确，或 stderr 含 Warning，截图步骤立即失败：不剥离输出、不重试其他屏幕、不保留伪 PNG。报告记录明确选择的物理屏幕 ID 和实际 ADB 命令。

## 执行 UI 计划

先通过正式 App 进入待测会话并选择明确运行模式，确认前置状态。复制并调整 `plans/simulated-touch.example.json` 或 `plans/direct-touch.example.json`，示例坐标不是跨设备通用的业务定位。

```sh
python3 validate-ui.py --adb-server local --serial emulator-5554 run \
  --plan plans/simulated-touch.example.json --output /absolute/path/new-run \
  --target-rotation 0 --input-manager global \
  --jar-sha256 77334e9f5ae344df36f9131b0416151f8e832e4ec0fb99dada116ef753ec1751
```

远程设备在全局参数中使用 `--adb-server remote --adb-host HOST --adb-port PORT --serial SERIAL`，其余相同。输出目录必须尚不存在，避免混用前次截图。

计划的 `reference` 必须写基准截图 `width`、`height`、`rotation`。角度是相对同一坐标基准顺时针的 `0/90/180/270` 度，不是 Android 的 0/1/2/3 枚举。`--target-rotation` 明确给本次屏幕方向；某一步在 App 界面旋转后，可在下一步覆写 `target_rotation`。

例如 2400×1080、rotation=0 的点映射至 1080×2400、rotation=90：先在归一化坐标做顺时针 `(x,y) -> (1-y,x)`，再缩放至目标截图像素。手势位移通过旋转后的终点减起点计算。截图横竖形状与指定方向冲突时退出；越界点退出。该换算只处理屏幕坐标，不推断窗口、DPI、黑边或 UI 自适应布局；目标布局变化时应重新采集参考点或使用 `tap_text`。

支持步骤：

| action | 参数 | 行为 |
| --- | --- | --- |
| `tap` | `point: [x,y]` | 单指点击 |
| `tap_text` | `text`，可选 `index` | 每次重新 dump UI 树，精确匹配 text/desc/resource-id；多匹配必须明确 index |
| `swipe` | `start`、`end`、`duration_ms` | 单指滑动 |
| `tap2` / `tap3` | `points`、`duration_ms` | 所选 InputManager API 的原生多指点按 |
| `swipe2` | `points`、`delta`、`duration_ms` | 原生双指滑动 |
| `key` | `key` | BACK/ENTER/ESCAPE/HOME/TAB/DPAD_UP/DOWN/LEFT/RIGHT |
| `capture` | 无 | 仅截图 |

`tap_text` 操作的是 Android App 可访问性树；Wayland/X11 内部控件通常不会出现在该树，应使用显式坐标。UI dump 错误时终止，禁止复用旧树。`settle_ms` 范围 0..60000，仅为观察等待，不表示业务成功。

每步保存 before/after PNG；文字定位另保存 XML；`report.json` 记录坐标变换结果、ADB 参数、时间、退出码及错误。ADB 成功只说明动作执行，不代表桌面业务通过。

## 显式基线截图比对

某一步增加 `"baseline": "baselines/menu.png"`，路径相对计划文件。可选 `channel_tolerance`（0..255，默认 0）。基线不自动生成，不以本次截图偷偷更新基线。所有基线和 Pillow 在发送 UI 动作前检查。

也可单独比对：

```sh
python3 validate-ui.py compare --baseline baseline.png --actual actual.png \
  --report comparison.json --diff difference.png --channel-tolerance 0
```

报告记录尺寸、SHA256、变化像素数/比例、平均通道差和变化范围；输出原始 RGB 差异图。尺寸不同只记录 `dimension_mismatch`，不自动 resize/rotate。需要机械阈值时显式加 `--max-changed-fraction 0.01`，超限返回 1；默认即使有差异也完成报告。所有报告均固定 `semantic_verdict=not_assessed`：动画、时钟、异步数据加载可能产生差异，业务结果必须人工结合界面证据判断。

## 验证范围

已完成 shell/Python 静态检查、5 项脚本测试（旋转/边界、远程 ADB 参数、UI dump 失败不复用旧树、截图差异报告不冒充业务通过、显式物理屏幕截图及 Warning 立即失败）和指定编译机实际 mt.jar 构建。主线程已安装到独立设备路径，并在模拟器原生 Plasma / System EGL 执行抽屉关闭、双指右键、Esc、再次双指右键的正式 UI 计划；截图人工确认菜单正常且桌面保持运行。报告和全部步骤截图位于持久验证目录的 `native-plasma-runner/`。未覆盖旧 mt.jar 或临时 ui.py。

```sh
python3 test-validate-ui.py
```
