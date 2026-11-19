# APK 手动发布

## ADDED Requirements

### Requirement: 同标签发布可重复执行

手动发布必须在现有 Release 中覆盖本次同名 APK 与 CHECKSUMS-sha256.txt、CHECKSUMS-sha512.txt、CHECKSUMS-md5.txt。不得因这些附件已存在而冲突，必须保持 tag、APK 文件名/类型与非本次附件。

#### Scenario: 同一 tag 重跑

- **WHEN** 新一轮构建发布到已有同名附件的 tag
- **THEN** 本轮 APK 与校验附件替换旧内容，校验结果对应新 APK，其余附件保留

#### Scenario: 新建 tag 或说明为空

- **WHEN** 目标 tag 还没有 Release，或者 release_notes 输入为空
- **THEN** 可以创建并上传 Release；已有 Release 的空说明输入不得清空原正文

### Requirement: 发布说明显式换行

默认中英文说明必须使用字面 \n 表示换行。上传前必须还原为真实换行，保留已有真实换行和其他反斜杠文本，不执行输入中的 shell 内容。

#### Scenario: 输入单行转义文本

- **WHEN** 输入包含标题、\n\n 空段落和 \n 分隔的列表
- **THEN** Release 正文保留对应标题、空行和独立列表条目

#### Scenario: 输入包含其他反斜杠

- **WHEN** 输入还含有 \t、\c 或 shell 语法文本
- **THEN** 这些内容保持字面值，不作为转义或命令执行
