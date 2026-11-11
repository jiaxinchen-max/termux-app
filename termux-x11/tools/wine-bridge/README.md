# wine-bridge

可维护的Wine侧启动和进程／键鼠桥接，Windows x86-64 PE程序。供Native、PRoot、Proton和Hangover启动链加载，不是Linux glibc或Android ELF。游戏手柄DLL直接访问Android，桥接程序不代理手柄接口。

## 构建

依赖MinGW-w64的x86-64 GCC及binutils：

```sh
sh termux-x11/tools/wine-bridge/build.sh /tmp/wine-bridge-build
```

输出`wine-bridge.exe`、完整PE、独立`wine-bridge.debug`及编译器版本。运行PE不包含调试符号，PE时间戳固定为零。验证后的运行PE复制到`termux-x11/src/main/assets/wine-bridge.exe`。APK不打包完整PE或调试符号。

```text
wine-bridge.exe [/dir DIRECTORY] [/affinity HEXMASK] PROGRAM [ARGUMENTS...]
```

目标先挂起创建，加入Job并设置可选亲和性后恢复。Job完成端口自然等待目标及派生进程全部退出；EXIT或桥接关闭时清理所属进程，没有运行超时或旧程序回退。返回根目标退出码；参数错误为2，准备失败为3，创建目标失败为4。

## 协议

仅监听`127.0.0.1:7946`，仅接受Android端口7947的64字节小端报文。编码见`protocol.c`，与Android `WinHandler`相同，不能按统一长度头解释所有命令。支持INIT、EXIT、EXEC、KILL_PROCESS、LIST_PROCESSES／GET_PROCESS、SET_PROCESS_AFFINITY、MOUSE_EVENT／CURSOR_POS_FEEDBACK、KEYBOARD_EVENT、BRING_TO_FRONT。未知、截断及超长请求拒绝执行并保留诊断；后续合法请求仍能处理。UDP EXEC的文件名与参数合计最多51字节；命令行启动不受此UDP限制。7948／7949不属于本程序。

## 验证

协议内存检查：

```sh
cc -std=c11 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined \
  -fno-omit-frame-pointer termux-x11/tools/wine-bridge/protocol.c \
  termux-x11/tools/wine-bridge/tests/protocol_test.c -o /tmp/wine-bridge-protocol-test
/tmp/wine-bridge-protocol-test
```

真实Wine测试需要独立的已初始化64位Wine prefix和1024×768的Xvfb显示，端口7946／7947必须空闲。测试创建真实Windows进程及窗口；探针和结果不打进APK：

```sh
x86_64-w64-mingw32-gcc -std=c11 -O2 -g -Wall -Wextra -Werror \
  -municode -mwindows -static-libgcc -Wl,--no-insert-timestamp \
  -o /tmp/wine-bridge-build/probe.exe termux-x11/tools/wine-bridge/tests/probe.c -luser32
DISPLAY=:92 WINEARCH=win64 WINEPREFIX=/absolute/path/to/isolated-prefix WINEDEBUG=-all \
  python3 termux-x11/tools/wine-bridge/tests/integration.py \
  --wine /usr/lib/wine/wine64 --bridge /tmp/wine-bridge-build/wine-bridge.exe \
  --probe /tmp/wine-bridge-build/probe.exe --output /tmp/wine-bridge-results.json
```

集成覆盖参数／Unicode／目录、进程字段、亲和性设置及继承、窗口／真实输入、异常报文、端口冲突、UDP执行及终止、自然退出、快速启动器派生链、无关进程隔离、EXIT及失败退出。主机Wine通过不等于设备上所有运行时已经验收，Native Wine／Proton／PRoot Wine／Hangover仍需手动验证。
