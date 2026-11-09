# 设计

## 已核实链路
QuickGameImport 先保存游戏和配置再打开 GameDetailActivity；运行准备由详情 Save 和 GameRuntimeOptionsView.saveAndFinish 调用 RuntimeWarmup。旧详情页只按目录访问状态启用 Launch，旧卸载器遇到活跃任务拒绝；桌面容器原先会隐藏运行中删除入口，停止中拒绝进入设置或更改运行参数。

## Launch 判断
GameEditorReadiness 为只读检查：Native 按当前 gameId 的任务及 Wine 选择判断，PRoot 按当前 containerId、基础包及绑定判断。无任务、任何关联任务仍在执行、最新任务失败/取消、当前环境凭据缺失或读取异常均不放行；历史成功和其他游戏的共享 Native prefix 不能代替当前游戏成功保存准备。正常成功后还检查对应环境、注册表及必要完成标记。保留现有结构性检查，不校验资源内容摘要和签名。

GameDetailActivity 集中更新 Launch enabled，点击前在 IO 执行器重新读取；字段编辑及嵌入配置修改立即关闭 Launch。页面恢复、任务文件事件、旋转重绑定时刷新，代际检查忽略过期回调，页面暂停后释放监听。缺失任务目录不创建、不监听，首次 Save 后页面转到现有控制台。资源管理、运行配置和删除不使用 Launch 门禁。

## 编辑与删除先强杀
RuntimeEditingStop 收集当前游戏及共享同一前缀的关联游戏、已记录任务和 API 已提交但未落盘任务。未终结任务持久化 CANCELLED，任务级取消标记禁止后续准备、状态覆盖及宿主再次提交。终结任务不改写其完成状态，但仍按对象私有路径清理真实存活进程，不依赖任务报告状态决定是否强杀。

PrefixSetupTasks/RuntimeSetupTasks 注册待执行命令；两个准备服务和启动服务在任务写入、依赖等待及实际提交边界检查取消，提交与强杀使用同一短临界区。TermuxService 在实际创建进程前再次检查取消标记，封住宿主 Intent 尚未执行的窗口。不能持有该锁等待组件下载。

宿主 RuntimeProcessKiller 按明确的私有 prefix/spec/log 路径匹配同 UID 进程，纳入子进程并排除 Android 主进程；先暂停选中的进程防止继续派生，再立即 SIGKILL。之后仅短暂确认内核终止结果，不走优雅退出或等待任务自然完成。失败向调用方报告，禁止继续删除。

详情首次载入和运行配置载入先执行强杀。GameUninstaller 在删除资产锁外完成停止，然后进入既有资产删除临界区，保持外部游戏目录和共享运行时边界。桌面设置入口和保存调用强杀；删除入口始终显示，先强杀后执行条目/容器删除。桌面及 C 盘归档工作线程通过 interrupt 和流式复制取消点终止，并完成其已有事务清理；仅等待停止确认，不等原任务自然结束，超时报告强杀失败。无关联的归档继续运行，不据其状态拒绝编辑。

## 修改边界与保护路径
涉及 termux-ui 游戏/桌面 Activity、API、卸载器、就绪及终止协调、准备和启动服务；app 宿主工厂、TermuxService 进程创建边界及进程终止实现。Service、宿主和任务写入边界属于本次高风险范围，用户关于“统一按照强杀”的明确命令作为范围授权，记录于 run manifest。仓库未提供保护路径策略文件，policy id 记为不可用，不虚构规则。

不修改 shell、X11 实现、输入、下载协议和运行时资产布局；Java 依赖等待增加取消检查。回滚按本变更文件和新增类撤销，保留前序资源校验移除、glibc 依赖及子模块修改。

## 验证
运行定向 JVM、Robolectric 界面与卸载测试；覆盖 Native/PRoot 所有准备状态、共享前缀隔离、先取消强杀后删除、准备/启动前取消标记、进程树和无关 UID/目录隔离、旋转和点击拦截。构建原名 arm64 APK，检查正常签名、testOnly=false 和 ABI。用户手动验证实际 Android 进程终止与 UI，代理不操作设备。Harness verify.sh、规定引导文件和 OpenSpec CLI 缺失，如实记录，不声明严格门禁通过。
