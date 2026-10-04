# 旧版 `.doc` 测试样例

这两个文件**不是**本项目的产物，取自 Apache POI 的官方测试数据集，用于给 `DocReader` 提供真实输入。

| 文件 | 来源 | 用途 | SHA-256 |
| --- | --- | --- | --- |
| `simple-table.doc` | `apache/poi` → `test-data/document/simple-table.doc` | 表格 → markdown；纯 `Normal` 段落（无标题样式） | `67D7FE8E0555C16516354498A5935F24EEEF90BEB5EFBF526F93A1D6C734DA43` |
| `Lists.doc` | `apache/poi` → `test-data/document/Lists.doc` | `Heading 1` 段落的 `getLvl()` = 0，验证「大纲级别认标题」 | `82B235ECE31662A9B1B414F32A556D203261296E22689470A79007F0B29C37FA` |
| `Word6.doc` | `apache/poi` → `test-data/document/Word6.doc` | Word 6 格式，验证报错话术（不支持就要说清楚） | `58EA3347378E85E7C8D19AD64E74AD533B2F51B30F047594B434F380712F8B50` |

两者均为 **Apache License 2.0**（与 Apache POI 同许可），仅作测试输入，不参与构建产物。

## 为什么必须用真实文件

HWPF（`.doc` 的 OLE2 二进制格式）有几处行为与直觉相反，只能实测得到，写死在这里免得下次再踩：

- **样式名是本地化的**：俄文文档里 `Normal` 叫 `Базовый`、`Table Normal` 叫 `Содержимое таблицы`。所以**不能**只靠样式名认标题。
- **`Paragraph.getLvl()` 是大纲级别，语言无关**：`Lists.doc` 里 `Heading 1` 那段 = `0`，正文 = `9`。这是 `.doc` 认标题最可靠的一路。
- **`TableRow.isTableHeader()` 不可信**：真实表格里也返回 `false`，表头只能按「第一行」处理。
- **单元格文本以 `\u0007` 结尾**，且一个单元格内可以含多个 `\r`（多段落），必须折叠。
- **Word 6 / Word 95 会抛 `OldWordFileFormatException`**（`Word6.doc`、`Word95.doc` 实测），`HWPFOldDocument` 是另一套 API。
