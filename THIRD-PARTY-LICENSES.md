# 第三方依赖与许可

本文列出交付物**直接依赖**的主要三方组件及其许可。仅供交付沟通与合规评审参考；
**正式清单请以实际打入 jar 的制品为准**——最稳妥的做法是在有网环境执行一次：

```bash
mvn -o org.codehaus.mojo:license-maven-plugin:2.4.0:aggregate-add-third-party
# 离线不可用时，联网执行：
mvn org.codehaus.mojo:license-maven-plugin:2.4.0:aggregate-add-third-party
```

产物在 `target/generated-sources/license/THIRD-PARTY.txt`，覆盖全部传递依赖。

---

## 直接依赖

| 组件 | 版本 | 许可 | 说明 |
| --- | --- | --- | --- |
| Spring Boot / Spring Framework / Spring Data Redis | 3.5.7 | Apache-2.0 | 应用骨架、Web、SSE、数据访问 |
| Apache Tomcat (spring-boot-starter-web 内嵌) | 随 Boot | Apache-2.0 | 内嵌 Servlet 容器 |
| Project Reactor (reactor-core) | 随 Boot | Apache-2.0 | 流式（SSE）实现 |
| LangChain4j（core / open-ai / reactor / elasticsearch） | 1.18.1 | Apache-2.0 | 模型调用、文档解析、向量存储适配 |
| LangGraph4j（core / langchain4j） | 1.8.17 | Apache-2.0（**待复核**） | 图编排与检查点 |
| Elasticsearch Java Client / Low Level REST Client | 9.4.4 | Apache-2.0 | ES 检索与索引管理 |
| Lettuce | 随 Boot | Apache-2.0 | Redis 客户端 |
| Apache Commons Pool 2 | 随 Boot | Apache-2.0 | Lettuce 连接池 |
| Jackson (databind / core / annotations) | 随 Boot | Apache-2.0 | JSON 序列化 |
| spring-security-crypto（仅 BCrypt） | 6.3.3 | Apache-2.0 | 密码哈希（不引 Security 过滤器链） |
| SLF4J API | 随 Boot | MIT | 日志门面 |
| Logback (classic / core) | 随 Boot | EPL-1.0 或 LGPL-2.1（双许可，择一） | 日志实现 |
| Micrometer (observation / commons) | 随 Boot | Apache-2.0 | 仅作观测 API 传递依赖，未启用指标后端 |
| Apache POI（poi / poi-ooxml / poi-scratchpad / xmlbeans） | 5.4.1 | Apache-2.0 | docx 解析：段落样式（Heading N）与大纲级别取标题层级、表格转 markdown；excel（.xls / .xlsx）解析：工作表转 markdown 表格（`WorkbookFactory` + `DataFormatter`）；旧版 doc 解析：HWPF 取大纲级别与表格（`poi-scratchpad` 是本轮唯一新增的构件） |
| Apache PDFBox（pdfbox / pdfbox-io / fontbox） | 3.0.8 | Apache-2.0 | pdf 解析：抽行（文本 + 字号 + 坐标 + 页码）、页眉页脚清洗、跨页段落拼接 |
| Apache POI 官方测试样例（3 个 `.doc`，**仅测试资源，不进交付物**） | 取自 `apache/poi` 的 `test-data/document/` | Apache-2.0 | `domainplane-infrastructure/src/test/resources/ingestion/doc/`：`simple-table.doc` / `Lists.doc` / `Word6.doc`。旧版 `.doc` 无法用代码现造，必须有真实二进制文件才能回归（来源与 SHA-256 见该目录 README） |
| Lombok | 随 Boot | MIT | 编译期代码生成（**不进入运行时**，编译期可选依赖） |

## 刻意未引入的组件（评审时请勿按"漏列"处理）

| 组件 | 未引入的原因 |
| --- | --- |
| `langchain4j-easy-rag`（及其传递依赖 Apache Tika 全家桶、`langchain4j-embeddings-bge-small-*`、onnxruntime、tokenizers、DJL、JNA） | 本项目未使用其任何类；引入会让 fat jar 从约 58MB 涨到约 249MB。文档解析走自研 `DocumentProcessStrategy`，向量化走远端 OpenAI 兼容 `EmbeddingModel`，均不需要 Tika 解析或本地 ONNX 模型 |
| Apache Tika（`tika-core` + `tika-parsers-standard-package`） | **不是体积原因，是能力方向相反**：Tika 的输出是"格式无关的纯文本"，实测对 docx 会丢掉标题层级（Heading 全变 `<p>`）、对 pdf 会丢掉字号与坐标 —— 而这两样正是入库切片要用的信息。所以 docx 直连 POI、pdf 直连 PDFBox，markdown 自研（Tika 也没有 markdown parser） |

## 需要交付方注意的两点

1. **Logback 是双许可（EPL-1.0 / LGPL-2.1）**：分发整包时按 EPL-1.0 履行即可，
   但若贵司政策排斥 LGPL 系许可，请在清单里明确选择 EPL-1.0 并保留其许可文本。
2. **Lombok 是 `provided` 作用域且已被显式排除**：它只参与编译，不进入 fat jar
   （`domainplane-server` 的 spring-boot-maven-plugin 配了 `<excludes>`，因为 provided 作用域仍会被 Boot 打包），
   通常无需列入分发许可，但源码分发场景下仍需保留其 MIT 许可声明。

## 本项目的许可

DomainPlane 本体以 **Apache-2.0** 分发（见根目录 `LICENSE`）。
分发时需随附该文件，并在显著位置保留版权与许可声明。
