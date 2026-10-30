# Discover AppStream 刷新隔离测试

测试使用实际 PackageKitBackend 插件、Common 库、AppStream 池与编译缓存。fixture 只模拟明确声明的 PackageKit D-Bus 服务事件，不连接生产服务；NetworkState 保持 unknown，不伪造在线。catalog 为自行构造的 KCalc 条目，不包含设备数据。

- `pool-test/`：实际池首次为空，外部编译器发布真实缓存后，同一池仍为空，新池能读取新组件。
- `main.cpp`：链接精确基线或候选插件，接收独立服务的真实 RefreshCache 完成事件；测试刷新重载、错误原文、资源索引及对象生命周期；覆盖同包多应用的 QVector 唯一结果及无应用映射的通用包查询。
- `fake-packagekit.py`：私有总线服务。RefreshCache 必须由测试显式 release 成功或失败；Resolve/GetUpdates 仅提供测试所需接口，不代表真实包安装。
- `run-test.py baseline|fixed`：每轮建立独立标准 metadata/cache/APT lists/HOME/runtime，禁用总线服务自动激活；使用真实 UID 保持 EXTERNAL 认证一致。为无关 ODRS 路径提供独立 fixture 文件，避免联网。

测试程序通过只改变 C++ 访问权限检查实际对象状态，不替换 backend 实现。worker semaphore 使加载期间的成功刷新事件确定性排队；真实本地 deb 在加载仍被阻塞时必须独立返回。旧查询与等待查询均为真实 ResultsStream。

运行仅在指定 arm64 编译环境，路径由 CMake 参数 `CANDIDATE_HEADERS`、`CANDIDATE_LIBRARY_ROOT` 显式选择。最终产物必须从 deb 提取后再链接验证，不能仅测试中间构建库。

本测试不覆盖正式 App UI，也不证明历史小米实例的完整失败输入已复现。旧实例缺少首次加载的精确时间及完整原始输入。

退休资源从活动索引移除，仍由 backend 持有至其析构，保证已有详情和流的引用有效。长会话多次 metadata 变化会增加退休对象占用；后续安全回收需明确跟踪 UI/事务/流引用，不能直接 deleteLater。

最终 ELF 验证记录实际加载映射，并同时要求退出码及明确的断言完成标记；不将 proot exit0 单独视为通过。测试的已知 AppStream 源/cache、HOME、XDG runtime 和总线目标独立；裸 guest `/tmp`、`/run` 未另行 bind。并行旧基线崩溃保留为未归因证据，串行同 binary 的 stale 复现独立记录。
