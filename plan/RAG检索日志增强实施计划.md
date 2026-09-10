# RAG 检索日志增强实施计划

## 一、目标

在现有 RAG 多轮问答流程中，增加**尽可能详细**的运行日志，完整打印「被召回的文档内容」，以及围绕检索的上下文信息，便于：
- 排查「回答不准 / 没用到知识库」等问题（确认到底召回、注入了哪些片段）
- 观察相似度分数、命中排序、topK 是否生效
- 多轮会话下按 sessionId 关联定位单次提问

当前痛点：`RagKnowledgeService.search` 仅返回结果、无任何日志；检索到的 chunk、分数、来源、最终拼进模型 System 提示词的内容都不可见，排障只能靠猜。

## 二、设计思路

日志点放在 **`ChatService`**（而非 `RagKnowledgeService`），原因：
- `sessionId`、`ragEnabled`、`topK` 都在 `ChatService` 可见，能完整关联单次提问
- 「最终注入模型的 System Prompt」是在 `ChatService` 拼装的，只有在这里才能打印到真正的注入内容
- `RagKnowledgeService.search` 不持有 sessionId，若在内部打日志无法按会话关联

### 2.1 打印的日志信息（按提问流程顺序）

一次问答触发的日志分四段：

1. **请求入口**
   - sessionId、message（问题全文）、ragEnabled、topK（解析后生效值）

2. **检索前**
   - 检索关键词（即 message）、目标 topK、检索开始时间

3. **检索后 / 逐条命中（核心，即「被召回的文档内容」）**
   - 命中总条数、检索耗时(ms)
   - 每条命中：**序号**、来源文件名(source metadata)、chunk_index、**相似度分数**(score)、完整文本全文
   - 文本长度（字符数），并在多行/超长文本时原样完整打印（不做截断）

4. **注入模型**
   - 拼装后的 System Prompt 的**字符总数**
   - System Prompt 全文内容（即实际喂给模型的知识片段拼接结果）
   - 当检索结果为空但 RAG 启用时，打印明确的「空召回」提示

### 2.2 日志级别与写入

- 默认级别 **DEBUG**，关键异常/空召回用 **WARN**
- 配置 **Logback 单独文件**（`rag-retrieval.log`）：
  - 按**天滚动**（`%d{yyyy-MM-dd}`），保留最近 30 天，避免无限增长
  - **UTF-8 编码**，规避 Windows 中文日志乱码
  - 单独归集 RAG 检索日志，不与业务/框架日志混杂，便于 grep `sessionId`
- 控制台同步输出 DEBUG 便于开发期实时观察（`logging.level.com.wuyunbin.rag=debug`）

### 2.3 涉及的代码改动点（已实施）

| 文件 | 改动 |
|---|---|
| `ChatService.java` | 增加分段日志 + **截图风格的结构化召回块**：请求入口 → 性能摘要(A) → `==== RAG 召回文档 ====` 块（conversationId / query / 召回数量 / 编号 doc[n] / 【正文】 / END 尾部）→ System Prompt 注入长度；超限保护(B)、脱敏开关(C)、单篇正文上限 |
| `application.properties` | 新增 `logging.level.com.wuyunbin.rag=debug`、`rag.log.mask`(C)、`rag.log.max-inject-chars`(B)、`rag.log.max-content-chars`(正文单篇上限)、`rag.log.retention-days`(D) |
| 新增 `logback-spring.xml` | 配置 RAG 专用滚动文件 `logs/rag-retrieval-日期.log`（UTF-8、按天滚动、保留天数用 springProperty 引用配置） |
| 新增 `src/test/.../test/ListAppender.java` | 测试工具：为指定 Logger 挂内存 appender 捕获 DEBUG 日志，用于断言块内容 |

> 不改动 RAG 检索逻辑、接口出入参、Session 持久化——纯日志/展示增强 + 超限兜底，正常窗口下行为零变更。

### 2.4 已确认一并纳入的增强点（讨论结果）

- **A 检索性能日志**：记录 `search` 耗时(ms)、命中条数、评分区间 `min~max`，评分普遍偏低时便于判断向量/切片质量问题
- **B System Prompt 超限保护**：注入前统计 System Prompt 字符数；超过 `rag.log.max-inject-chars`（默认 1_000_000，衔接「上下文 1M」）时打 WARN 并做**保头保尾**截断（保留开头协议 + 结尾最新知识），避免超窗口出错
- **C 敏感信息脱敏开关**：`rag.log.mask=false`（默认关，保持详细）；开启后对 chunk 做手机号(11位)、邮箱、URL 简单脱敏后再打印
- **D 保留策略可配**：日志保留天数、是否独立文件、滚动粒度均读到 `rag.log.*` 配置；Logback 用 `springProperty` 引用

### 2.5 日志分级策略

- 请求入口、检索前、注入 Prompt 摘要 → **DEBUG**
- 结构化召回块（含 A 的耗时/评分） → **DEBUG**
- 空召回、System Prompt 超限触发截断 → **WARN**
- 默认 `logging.level.com.wuyunbin.rag=debug`（`RagApplicationTests` 上下文加载不受影响）

### 2.6 结构化召回块输出示例（已最终确定）

块内多行无逐行日志前缀，便于阅读与归档；正文按 `rag.log.max-content-chars`（默认 10 万）截断，即「尽可能详细」：

```
[rag][{sessionId}] 检索完成  命中=3 耗时=12ms 评分区间=[0.7421 ~ 0.8843]

================== RAG 召回文档 ==================
conversationId : eba514ec-6898-454d-9b6c-e4937be8ee33
query          : 校史？
召回数量        : 3
---- doc[0] id=doc-0, metadata={distance=0.8843, chunk_index=0, source=校史.txt}
      【正文】厦门理工学院创办于1981年……
---- doc[1] id=doc-1, metadata={distance=0.8122, chunk_index=1, source=校史.txt}
      【正文】……
------------ END (共 3 篇，每篇打印 100000 字符) ------------
```

（早期方案中的“逐条 `>>>START/END<<<`”输出已由该结构化块取代；字段对齐按中文显示宽度处理。）

## 三、验收标准

1. 启动应用并调用 `POST /api/chat`（RAG 启用），日志中出现请求入口、性能摘要(A)、结构化召回块（2.6 格式）、System Prompt 注入长度
2. 每条召回 chunk 全文完整打印（受 `rag.log.max-content-chars` 上限，默认 10 万），带 doc 编号、metadata（distance/chunk_index/source）、【正文】
3. A：日志含检索耗时、命中条数、评分区间 min~max
4. B：System Prompt 超 `rag.log.max-inject-chars` 时打 WARN 并保头保尾截断后注入
5. C：`rag.log.mask=true` 时 chunk 中手机号/URL/邮箱被脱敏；false（默认）时原文完整
6. D：`rag.log.retention-days` 等可配项能通过配置生效
7. `rag-retrieval.log` 按天滚动、UTF-8 无乱码、内容可按 sessionId 检索
8. 不影响既有测试，`mvn verify` 通过（含新增：结构化块多篇/metadata、脱敏、超限截断、日志捕获），覆盖率 ≥ 30%（实测约 77%）

## 四、测试策略（已实施）

- 现有单元/集成测试不受影响（正常窗口下行为零变更）
- 增补 `ChatService` 单元测试，用 `ListAppender` 捕获并断言：
  - 召回 chunk 全文、来源、分数已被打印（结构化块含 conversationId/召回数量/doc[n]）
  - 结构化块多篇 doc 与 metadata（source / chunk_index）正确
  - System Prompt 超限触发保头保尾截断（A/B）
  - `mask` 对手机号、邮箱、URL 的脱敏与 `null` 处理（C）
- 集成测试验证：RAG 检索链路在 DEBUG 下日志可产出、会话落盘、错误统一响应

> 已全部实施并验证：`mvn verify` 通过，38 个测试全部成功，行覆盖率约 77.4%（≥ 30% 门槛）。计划实现完成。

---

## 附：增强点讨论记录

以下四项经确认**均已在本次一并纳入**并实施（见 2.4）：

### A. 检索性能日志（推荐纳入）
打印 `search` 返回值映射后的检索耗时、命中评分区间 `min~max`，一眼判断是否命中质量差（全局分数偏低常意味着向量模型/切片问题）。

### B. System Prompt 超限保护与提示（推荐纳入）
若注入的 System Prompt 过大（例如字符数超模型窗口），打 **WARN** 并触发分段截断策略（保头保尾），避免超上下文导致报错——与「上下文范围 1M」诉求衔接。

### C. 敏感信息脱敏开关（可后续）
若知识库文档含手机号/身份证等，日志完整打印会外泄。计划支持一个 `rag.log.mask=true` 开关，开启后对 chunk 做简单脱敏。默认关（即默认保持详细）。

### D. 日志保留策略可配（可选）
保留天数、是否单独文件均可配到 `application.properties`，而非写死。

请在下方选择你希望本次一并纳入的选项，确认后我再进入代码实施，并同步更新本计划的「改动范围」。