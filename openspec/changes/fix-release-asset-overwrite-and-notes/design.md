# 发布流程设计

仅调整 .github/workflows/attach_debug_apks_to_release.yml。构建矩阵、原有 APK 复制命名逻辑与产物类型保持。Activity、Service、任务状态、X11、输入、存储、组件下载等业务实现均不改动；shell 改动只在 GitHub Actions 上传作业执行。

发布说明默认值使用包含字面 \n 的单行 YAML 单引号字符串，标题、空段落及列表均显式编码。通过环境变量将输入交给 Python，仅替换字面 \n；不使用 eval 或 unicode_escape，不将用户文本插入 shell 代码，不解释 \t、\c 等其他转义。已有真实换行继续支持。

创建/更新 Release 步骤总是执行：非空说明更新正文，空说明在已有 Release 时保留正文；新 Release 即使说明为空也可创建。Release 的 tag 不移动，使用现有标题与目标提交行为。

用 GitHub CLI 代替上传 Action，对本次 APK 计算原有 sha256、sha512、md5 三份 CHECKSUMS-<算法>.txt，保持“release-apks/文件名、制表符、摘要”的原条目格式。一次调用 gh release upload --clobber 上传 APK 和三份校验文件，仅覆盖同名文件，不批量删除 Release 或 tag。按目标 tag 串行化本 workflow，正在执行的发布不中断，避免相同附件并发覆盖。

仓库缺少 protected paths 注册表与 Harness bootstrap。此处修改是用户明确要求的发布 CI 路径；影响仅为手动 workflow 的附件覆盖与说明格式，未授权运行 workflow 或实际发布。回滚恢复 workflow，验证包括实际 YAML/actionlint、离线模拟 GitHub CLI 的同标签二次发布/新标签/空说明/校验附件覆盖与换行检查；严格门禁需尝试并如实记录缺失状态。
