# 设计

PRoot 确认对话框移除 CheckBox 与分支删除选择，消息包含容器别名、全部数据不可恢复及导出备份保留。确认后调用已有 deleteAndRemoveContainer；取消不调任务。native 使用独立消息，继续 tasks.delete，不误导为卸载宿主程序或删除宿主文件。

底层已有 forceStop、安装互斥、受控别名和显式 proot-distro remove 保持不变；归档文件不在删除调用范围内。模型将 prootDistro 定义为桌面独占容器实例，本次不扩大删除到其他 alias，不新增磁盘删除实现。删除仍通过原有后台任务提交，提交后的完成和失败呈现不在本次交互简化范围。

验证中英文 XML、Activity 编译、现有桌面脚本回归；禁止使用用户容器做删除测试。代码审查关注取消路径、PRoot/native 分流和备份保留的实现依据。回退只撤销 Activity 和字符串变更，不能恢复用户主动删除的容器，因此确认必须明确不可恢复。

仓库没有指定 Harness 规则文件、verify.sh 或 OpenSpec CLI，手工维护规划与验证记录，不宣称严格门禁通过。不涉及额外受保护路径。
