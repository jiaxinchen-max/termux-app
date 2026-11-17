# PRoot 导出文件名包含容器名称

按照用户直接要求，在 PRoot 容器导出文件名中加入导出时的容器显示名称，便于识别备份。生成格式为“容器名称-ID-时间.desktop.tar.gz”，保留现有 ID、时间、后缀、固定目录及重名处理。

默认/中文名称保留；文件系统不接受的字符替换为下划线，名称部分按 UTF-8 字节限长，避免导出失败。存档内部元数据仍保存完整原名，恢复行为保持兼容。

无 HTTP API，api_docs 为 N/A；product_docs 为 Required，目标 docs/product/desktop-container-archive.md，用户验收前一致性 Pending。
