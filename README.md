# 萌芽（sprout_agent）

萌芽是基于 Spring Boot 的豆包风格 AI 助手。对话使用 DeepSeek，联网搜索使用 Tavily；文本向量模型与视觉模型通过 `.env` 中的 OpenAI 兼容服务配置。

## 技术栈

- Java 21、Spring Boot 3.5.6、Spring AI 1.1.0
- MySQL 8、MyBatis-Plus 3.5.5
- MySQL 是应用主数据库（对话与业务表）；PostgreSQL 仅用于 pgvector。
- PostgreSQL + pgvector、MinIO
- Apache PDFBox 2.0.30、Apache POI 5.2.5、Python `python-pptx` 1.0.2
- Reactor 3.7.11

## 运行

项目根目录 `.env` 保存本机凭证（包括 DeepSeek API Key 和 Nacos 登录凭证），已被 `.gitignore` 排除。Spring Boot 从工作目录按 properties 格式读取 `.env`；可通过 `./run.sh` 或以项目根目录为工作目录从 IDE 启动。操作系统环境变量优先于 `.env`：

```sh
./run.sh
```

PPT 文件渲染使用项目内的 Python 脚本。首次运行先执行 `python3 -m venv .venv && .venv/bin/pip install -r requirements.txt`；服务默认调用 `.venv/bin/python3`，其他部署路径可设置 `sprout.ppt.python`。渲染结果保存在 MinIO，完成后可通过 `GET /api/ppt/conversations/{conversationId}/download` 下载；旧任务停在 `RENDER` 时，发送“继续”即可生成文件。

Nacos 地址为 `10.244.0.111:8848`，命名空间 `sprout_agent`，配置 Data ID `sprout-agent-zerotier.yaml`、Group `DEFAULT_GROUP`。DeepSeek API Key 通过 `${SPRING_AI_OPENAI_API_KEY}` 从本机环境读取，不存入 Nacos。
`application.yml` 使用 Spring Cloud Alibaba Nacos Config 导入 `sprout_agent` 命名空间的 YAML；无需自定义配置加载器。修改 Nacos 配置后重启应用生效。
Tavily API Key 存于 `sprout_agent` 命名空间的 Nacos 配置 `sprout-agent-zerotier.yaml`，字段为 `sprout.tavily.api-key`。应用启动时通过 <https://mcp.tavily.com/mcp/> 建立 Streamable HTTP MCP 会话，注册服务端工具供 DeepSeek 按需调用，不再直连 Tavily REST API。修改配置后重启应用生效。Nacos 中的 API Key 同样是敏感凭证，应限制配置读取权限，不要将配置导出到公开仓库。

服务启动后通过 Nacos Discovery 注册为 `sprout-agent`，在 Nacos 服务中心切换到 `sprout_agent` 命名空间查看。优先注册本机 `10.244.*` ZeroTier 地址；停止服务后实例会自动注销。
PPT 配图使用阿里百炼 `qwen-image-2.1-turbo`：本机 `.env` 配置 `SPRING_AI_OPENAI_IMAGE_BASE_URL=https://trial.cn-beijing.maas.aliyuncs.com/compatible-mode/`（末尾斜杠必需，Spring AI 会追加 `v1/images/generations`）、独立的 `SPRING_AI_OPENAI_IMAGE_API_KEY`、`SPRING_AI_OPENAI_IMAGE_OPTIONS_MODEL=qwen-image-2.1-turbo`。试用域名有较低限流；生产环境可将 Base URL 改为本业务空间在北京地域的专属域名。`SPROUT_PPT_IMAGE_PUBLIC_BASE_URL` 要指向部署服务可从渲染器访问的地址（本机 ZeroTier 为 `http://10.244.0.106:8080`，更换机器或端口时同步调整）。图片生成后上传 MinIO，由服务的 `/api/ppt/images/{imageId}` 提供持久访问。不要将密钥提交到仓库或公开的 Nacos。缺少可用模型或可访问地址时，包含配图的 Schema 会保存于 MySQL，任务停在 `FAILED`（失败阶段为 `SCHEMA`）；配置就绪后用同一 conversationId 发送“继续”即可从已有 Schema 恢复，不重新生成已成功上传的图片。
PPT 生成过程通过 SSE `thinking` 事件逐阶段推送；每张配图先由对话模型将 Schema 中的描述改写为适合公开展示的画面描述，再调用文生图模型，报告每次优化、生成、上传 MinIO 和保存状态。改写失败时不会发送原描述。前端将阶段信息合并到固定高度、可滚动的进度框中，历史会话可查看已保存的进度；生成成功后，下载入口固定显示在输入框上方，不再作为单独的对话消息。
若文生图接口返回 `DataInspectionFailed`，系统会将上次被拒绝的描述交给对话模型重新改写，再生成图片；每张图最多调用文生图模型 3 次，三次均被拒绝才标记 `FAILED/SCHEMA` 并提示用户调整主题或配图要求。其他模型错误、改写失败或上传失败不重试。已保存的配图不会丢失；发送“继续”可从未完成的配图恢复，但服务商的输出图片审核仍可能拒绝新结果。
PPT 每个阶段完成后更新 MySQL 状态；失败时记录失败阶段和原因，向 SSE 输出可读说明，并把说明与本轮进度写入会话。需求不完整会停在 `FAILED`（失败阶段 `REQUIREMENT`），补充需求后从澄清阶段恢复；其他失败发送“继续”或“重试”从失败阶段恢复，不重复已完成的配图。点击停止保留当前检查点，同会话再次发送“继续”即可恢复；明确要求“新建/重新生成”才创建新实例。已成功的 PPT 不会因“继续”重复生成。

打开 <http://localhost:8080>。联网聊天使用 `POST /api/chat`，文件聊天先通过 `POST /api/files` 上传，再调用 `POST /api/file-chat` 并传入 `fileId`；PPT 生成入口使用 `POST /api/ppt`。三种接口均返回 `text/event-stream`（SSE）。

每次请求根据 `conversationId` 从 MySQL 读取最近的问答，重建本次请求专属的 `MessageWindowChatMemory`；运行期消息列表单独承载当前问题、流式模型输出和工具调用结果。正常完成时保存当前问题、最终回答及回合明细；用户停止生成或客户端断开时，保存已经输出的部分回答（尚无文本则保存“回答已停止”），使下一轮“继续”可以读取原问题和已输出内容。模型或数据库异常仍不保存失败回合。重启或多实例请求可从数据库恢复历史；工具调用中间消息不注入下次模型上下文。

- `GET /api/conversations`：最近 50 条对话
- `GET /api/conversations/{id}`：对话及消息；每条助手消息包含 `thinking`（模型返回的推理文本，可能为 `null`）、`references` 来源数组和 `recommendations` 推荐问题数组。附带文件的用户消息另有 `fileId` 和 `fileName`。
- `DELETE /api/conversations/{id}`：删除对话
- `POST /api/files`：`multipart/form-data`，表单字段名为 `file`；返回 `{"fileId":"32位小写十六进制","name":"文档.pdf","contentType":"application/pdf","size":12345}`（响应字段不因索引而改变）。支持 PDF、DOCX、UTF-8 TXT/MD、PNG/JPEG/WebP/GIF，最大 10 MiB；没有可提取文字的文档返回 400。上传的原文件存入 MinIO，文件元数据存入 MySQL；不超过 12000 字符的文档文字直接存于 MySQL，超过 12000 字符的文档按片段索引至 pgvector（`vector_file_info`），MySQL `uploaded_files.extracted_text` 留空。
- `POST /api/chat`：`{"conversationId":null,"message":"你好"}`，使用联网搜索 Agent；新对话传 `null`，继续对话传已有 ID。
- `POST /api/file-chat`：`{"conversationId":null,"message":"请总结附件","fileId":"上传返回的fileId"}`，`fileId` 必填，使用文件 Agent。当前消息只提供附件名称和 fileId，不注入正文；模型必须先调用仅在文件 Agent 中可用的本地 `loadContent(fileId,question)`，才能基于返回内容回答。短文档由工具直接读取 MySQL 文本；已索引长文档按当前问题从 pgvector 检索最相关的 5 个片段，可用不同问题多次检索。图片首次提问时从 MinIO 读取，由 `SPROUT_VISION_BASE_URL` / `SPROUT_VISION_MODEL` 视觉模型识别并将描述存入 MySQL 的 `uploaded_files.extracted_text`；工具读取缓存的描述，后续提问（包括服务重启后）不再识别图片，仅首次识别推送 `thinking`。识别失败返回 `error`，不会存入虚构描述。两种聊天的 SSE 均发送 `conversation`、可选的 `reasoning`（模型推理文本分片）、可选的 `thinking`（工具状态）、`delta`、可选的 `reference`、可选的 `recommend` 和 `done`；异常发送 `error`。前端实时展示推理，正文开始或流结束时自动折叠；历史对话可再次展开。
- `POST /api/ppt`：`{"conversationId":null,"message":"生成一份5页绿色能源科普PPT，面向大学生"}`，使用独立 PPT Agent；返回 `conversation`、阶段 `thinking`、流式 `delta` 和 `done`，失败时返回 `error`。流程包含需求澄清 → Tavily 资料搜索 → `templates/ai.pptx` 模板选择 → 大纲 → 页面 Schema JSON → 配图生成及 MinIO 持久化，状态推进到 `RENDER`；**尚不生成或下载 PPT 文件**。需求不足时状态保留 `REQUIREMENT`；失败后可从上次完成的阶段恢复。修改已有 PPT 尚不可用。
- `GET /api/ppt/conversations/{conversationId}`：读取最新 PPT 实例的状态、需求、搜索摘要、模板编码、大纲、`pptSchema` JSON 字符串和错误信息。没有 PPT 实例返回 404。图片 URL 指向 `GET /api/ppt/images/{id}`，由应用从私有 MinIO 返回有效图片；每成功上传一张图片就持久化一次 URL。已完成请求的消息也存入普通对话历史。
- `PATCH /api/chat/{conversationId}/stop`：停止该会话正在运行的联网、文件或 PPT 任务，向原 SSE 发送 `stopped` 后结束；聊天 Agent 保存已生成的部分回答，PPT Agent 保留上一个持久化阶段，下次继续执行当前阶段。无运行任务时返回 404；同一实例内三种 Agent 共享任务管理器，同一会话同时只允许一个任务。任务状态保存在内存中，多实例部署时停止请求必须路由到执行该任务的实例。
SSE 输出采用 Reactor `unicast().onBackpressureBuffer`，每个会话最多暂存 256 个事件；消费端跟不上时取消生成并让流以“客户端消费过慢，已停止生成”错误结束。聊天 Agent 保存已输出的部分答案，PPT Agent 只保留最近成功提交的阶段（不保存未完成阶段的分片）。缓冲上限只约束应用内的 SSE 队列。
- `GET /api/conversations?archived=true&query=关键词`：按状态与标题筛选最近 50 条，按 `updatedAt` 倒序；省略参数时仅返回未归档对话。
- `PATCH /api/conversations/{id}/archive`、`PATCH /api/conversations/{id}/restore`：归档与恢复，均返回 204。
- `PATCH /api/conversations/{id}/title`：请求 `{"title":"新标题"}`，去除首尾空格后须为 1–50 字符；重命名不改变最新消息时间。
前端对流式回答及历史回答统一渲染 Markdown，渲染后的 HTML 经 DOMPurify 清理；用户输入仍按纯文本展示。Markdown 解析器和清理器从固定版本的 jsDelivr 加载，无法加载时回答退回纯文本。参考来源默认折叠，展开后鼠标悬停或键盘聚焦链接可预览摘要，点击链接在新标签页打开有效 HTTP(S) 来源。
侧边栏只显示最近 50 条未归档对话，按最新消息时间倒序排列。每条标题右侧依次为重命名和归档按钮；输入新标题后按 Enter 保存、Esc 取消。历史对话窗口（移动端顶部历史按钮）也支持重命名、按标题搜索、归档和恢复。归档不删除消息，不改变最后消息时间；归档状态下不能继续发送，需先恢复。



聊天使用流式 ReAct 轮次：识别工具调用、合并同一调用的参数分片、并发执行本轮可用的工具，并将工具结果（包括失败信息）回填当前上下文供下一轮推理；开始执行时推送 `thinking` 状态。联网 Agent 只提供 Tavily MCP 工具，文件 Agent 只提供本地 `loadContent` 工具；文件回合首次读取成功前强制工具调用，失败或没有可用内容时不凭空回答。无工具调用即结束；超过 `sprout.agent.max-rounds` 后不再提供工具，强制生成最终回答。默认最多 5 轮；设置为 0 表示不限制轮次。`sprout.agent.max-messages` 控制每次注入模型的历史消息数，必须为至少 2 的偶数；Nacos YAML 配置为 20（至多最近 10 轮完整问答），只限制推理窗口，不删除 MySQL 的完整历史。
最终答案生成后，使用历史窗口、当前问题与完整回答进行一次非流式模型调用，生成恰好 3 个不同的推荐问题。默认启用，可用 `sprout.agent.enable-recommendations: false` 关闭；推荐仅用于展示和持久化，不进入后续对话的推理记忆。


启动时，`schema-mysql.sql` 在 MySQL 中创建对话、回合明细、`uploaded_files` 文件元数据及 `ai_ppt_inst` PPT 阶段实例表；旧库启动时自动补齐 `ppt_schema` 列。PPT 实例按状态持久化需求、搜索摘要、模板、大纲和 Schema；失败时记录失败阶段，重启后可从该阶段恢复。`PptTemplateCatalog` 从实际 `.pptx` 读取 shape 名称/类型，生成结构约束；Schema 校验页数、模板页型、字段名称与类型，超长文本按 Unicode 字符截断。pgvector 中 `vector_file_info` 保存长文档片段。删除对话时外键级联清理聊天记录和 PPT 实例，但已上传的 MinIO 文件不会自动删除。

当前还没有用户登录和对话隔离：可访问此应用的访客都能查看或删除历史对话。仅限可信内网使用；公开部署前需加入身份认证与按用户授权。

## 代码结构

`controller` 只处理 HTTP/SSE 和请求校验；`agents/BaseAgent` 承载聊天与文件 Agent 共用的流式 ReAct、会话记忆、持久化和停止流程。`agents/ppt/PptBuilderAgent` 独立调度 PPT 状态机，`PptStateStrategyFactory` 分派需求澄清、Tavily 信息收集、模板选择、大纲与 Schema 策略；`PptInstService` 管理 MySQL 断点，`PptImageService` 负责文生图和 MinIO 图片回填，`SimpleReactAgent` 手动执行搜索工具。`AgentTaskManager` 按会话管理三种 Agent 的运行任务；`UploadedFileService`、`FileVectorService` 负责文件与向量检索。各层位于 `src/main/java/com/sprout/agent/`。

`prompts/PptBuilderPrompts` 定义 Schema JSON 生成约束；`src/main/resources/templates/ai.pptx` 是当前模板，Apache POI 在启动时解析幻灯片的实际 shape 名称。`src/main/resources/python/render_ppt.py` 属于后续文件渲染阶段，当前未接入状态机。

## ZeroTier 服务

配置来源：`/Users/hongwei/dev/workspace/serve` 中的服务器文档及项目根目录 `.env`。

- MySQL：T480s `10.244.0.111:3306`
- pgvector：T480s `10.244.0.111:5433`
- MinIO：Debian `10.244.0.102:9000`

启动前需在远端准备独立的 `sprout_agent` MySQL/PostgreSQL 数据库；项目不复用服务器上其他应用的数据。远端服务凭证保存在本地 `.env`，不要提交或复制到公开日志。

`.env` 中的 DeepSeek API Key 和 `deepseek-flash` 模型已由 Spring AI 加载。向量模型为 `qwen3-embedding:4b`（1024 维）；视觉模型地址和模型名为 `SPROUT_VISION_BASE_URL`、`SPROUT_VISION_MODEL`。

`SPROUT_VISION_BASE_URL` 指向兼容 OpenAI 聊天接口的视觉模型服务根地址（不含 `/v1`），`SPROUT_VISION_MODEL` 是其模型名；如果服务要求鉴权，可设置可选的 `SPROUT_VISION_API_KEY`（本地 Ollama 无须设置）。图片视觉描述由该模型生成，DeepSeek 只接收描述文本，不直接接收图片媒体。

## 其他

应用默认连接上述 ZeroTier 地址。若使用本地 Docker 服务，需显式设置 `MYSQL_URL`、`PGVECTOR_URL`、`MINIO_ENDPOINT` 及对应本地账号密码后再运行。

`python-pptx` 是 Python 文档处理依赖，通过 `python3 -m pip install -r requirements.txt` 安装；其余依赖由 Maven 管理。pgvector 维度由 `PGVECTOR_DIMENSIONS` 配置，必须与 embedding 模型的维度一致。
