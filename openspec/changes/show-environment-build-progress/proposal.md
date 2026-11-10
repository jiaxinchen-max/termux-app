# 构建执行状态提示

## 问题与目标
用户截图显示Native正在安装软件包，但Status仍为Not installed，Build from network仍显示Download and install。关闭日志后无法直接判断任务是否在执行。

Native与PRoot构建入口增加持续的执行中提示。提示以内嵌文案和小型进度指示呈现，不覆盖页面、不禁用点击、不强制打开日志。任务执行中点击对应Build入口可查看已有任务日志，不重复创建任务。其他页面导航和安全操作保留现有行为。

## 范围
复用组件安装、基础RootFS构建和重建清理任务状态，覆盖排队、执行中、完成和失败，以及返回页面或Activity重建后的恢复。保持现有任务互斥、防重复提交与失败终止规则；不改变后台执行、任务重试或安装流程。不显示无法可靠计算的百分比。

## 文档与验证
api_docs=N/A，无HTTP契约变化。product_docs=Required，目标docs/product/component-management.md，验收前一致性Pending。验证Native/PRoot状态、关闭日志后持续可见、入口可点击、无重复提交、结束状态刷新及生命周期恢复。设备测试由用户手动完成。
