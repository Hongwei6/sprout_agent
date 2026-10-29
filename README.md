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

Nacos 地址为 `10.244.0.111:8848`，命名空间 `sprout_agent`，配置 Data ID `sprout-agent-zerotier.yaml`、Group `DEFAULT_GROUP`。DeepSeek API Key 通过 `${SPRING_AI_OPENAI_API_KEY}` 从本机环境读取，不存入 Nacos。
`application.yml` 使用 Spring Cloud Alibaba Nacos Config 导入 `sprout_agent` 命名空间的 YAML；无需自定义配置加载器。修改 Nacos 配置后重启应用生效。
Tavily API Key 存于 `sprout_agent` 命名空间的 Nacos 配置 `sprout-agent-zerotier.yaml`，字段为 `sprout.tavily.api-key`。应用启动时通过 <https://mcp.tavily.com/mcp/> 建立 Streamable HTTP MCP 会话，注册服务端工具供 DeepSeek 按需调用，不再直连 Tavily REST API。修改配置后重启应用生效。Nacos 中的 API Key 同样是敏感凭证，应限制配置读取权限，不要将配置导出到公开仓库。

服务启动后通过 Nacos Discovery 注册为 `sprout-agent`，在 Nacos 服务中心切换到 `sprout_agent` 命名空间查看。优先注册本机 `10.244.*` ZeroTier 地址；停止服务后实例会自动注销。

打开 <http://localhost:8080>。联网聊天使用 `POST /api/chat`，请求体 `{"message":"你好"}`；文件聊天先通过 `POST /api/files` 上传，再调用 `POST /api/file-chat` 并传入 `fileId`。两者均返回 `text/event-stream`（SSE）。

每次请求根据 `conversationId` 从 MySQL 读取最近的问答，重建本次请求专属的 `MessageWindowChatMemory`；运行期消息列表单独承载当前问题、流式模型输出和工具调用结果。正常完成时保存当前问题、最终回答及回合明细；用户停止生成或客户端断开时，保存已经输出的部分回答（尚无文本则保存“回答已停止”），使下一轮“继续”可以读取原问题和已输出内容。模型或数据库异常仍不保存失败回合。重启或多实例请求可从数据库恢复历史；工具调用中间消息不注入下次模型上下文。

- `GET /api/conversations`：最近 50 条对话
- `GET /api/conversations/{id}`：对话及消息；每条助手消息包含 `references` 来源数组和 `recommendations` 推荐问题数组（没有时为空数组）。附带文件的用户消息另有 `fileId` 和 `fileName`，其余消息这两个字段为 `null`。
- `DELETE /api/conversations/{id}`：删除对话
- `POST /api/files`：`multipart/form-data`，表单字段名为 `file`；返回 `{"fileId":"32位小写十六进制","name":"文档.pdf","contentType":"application/pdf","size":12345}`。支持 PDF、DOCX、UTF-8 TXT/MD、PNG/JPEG/WebP/GIF，最大 10 MiB；没有可提取文字的文档返回 400。上传的原文件存入 MinIO，文件名、媒体类型、大小及提取文字存入 MySQL；提取文字至多 100000 字符，超出时标明截断。
- `POST /api/chat`：`{"conversationId":null,"message":"你好"}`，使用联网搜索 Agent；新对话传 `null`，继续对话传已有 ID。
- `POST /api/file-chat`：`{"conversationId":null,"message":"请总结附件","fileId":"上传返回的fileId"}`，`fileId` 必填，使用文件 Agent。文档文字加入当前用户消息；图片首次提问时从 MinIO 读取，由 `SPROUT_VISION_BASE_URL` / `SPROUT_VISION_MODEL` 视觉模型识别并将描述存入 MySQL 的 `uploaded_files.extracted_text`。后续提问（包括服务重启后）直接加载该描述注入上下文，不再识别图片；仅首次识别推送 `thinking`。识别失败返回 `error`，不会存入虚构描述。两种聊天的 SSE 均发送 `conversation`、可选的 `thinking`、`delta`、可选的 `reference`、可选的 `recommend` 和 `done`；异常发送 `error`。
- `PATCH /api/chat/{conversationId}/stop`：停止该会话正在运行的联网或文件聊天模型流，保存已生成的部分回答，向原 SSE 发送 `stopped` 后结束；无运行任务时返回 404。同一实例内，两种 Agent 共享任务管理器，同一会话同时只允许一个任务；并发重复请求失败。任务状态保存在内存中，多实例部署时停止请求必须路由到执行该任务的实例。
SSE 输出采用 Reactor `unicast().onBackpressureBuffer`，每个会话最多暂存 256 个事件；消费端跟不上时不丢弃或无限累积，而是取消生成、保存已经输出的部分答案并让流以“客户端消费过慢，已停止生成”错误结束。缓冲上限仅限制应用内的 SSE 队列，不代表浏览器或网络传输也有相同上限。
- `GET /api/conversations?archived=true&query=关键词`：按状态与标题筛选最近 50 条，按 `updatedAt` 倒序；省略参数时仅返回未归档对话。
- `PATCH /api/conversations/{id}/archive`、`PATCH /api/conversations/{id}/restore`：归档与恢复，均返回 204。
- `PATCH /api/conversations/{id}/title`：请求 `{"title":"新标题"}`，去除首尾空格后须为 1–50 字符；重命名不改变最新消息时间。
前端对流式回答及历史回答统一渲染 Markdown，渲染后的 HTML 经 DOMPurify 清理；用户输入仍按纯文本展示。Markdown 解析器和清理器从固定版本的 jsDelivr 加载，无法加载时回答退回纯文本。参考来源默认折叠，展开后鼠标悬停或键盘聚焦链接可预览摘要，点击链接在新标签页打开有效 HTTP(S) 来源。
侧边栏只显示最近 50 条未归档对话，按最新消息时间倒序排列。每条标题右侧依次为重命名和归档按钮；输入新标题后按 Enter 保存、Esc 取消。历史对话窗口（移动端顶部历史按钮）也支持重命名、按标题搜索、归档和恢复。归档不删除消息，不改变最后消息时间；归档状态下不能继续发送，需先恢复。



聊天使用流式 ReAct 轮次：识别工具调用、合并同一调用的参数分片、并发执行 Tavily MCP 工具，并将工具结果（包括失败信息）回填当前上下文供下一轮推理；开始执行时推送 `thinking` 状态。无工具调用即结束；超过 `sprout.agent.max-rounds` 后不再提供工具，强制生成最终回答。默认最多 5 轮；设置为 0 表示不限制轮次。`sprout.agent.max-messages` 控制每次注入模型的历史消息数，必须为至少 2 的偶数；Nacos YAML 配置为 20（至多最近 10 轮完整问答），只限制推理窗口，不删除 MySQL 的完整历史。
最终答案生成后，使用历史窗口、当前问题与完整回答进行一次非流式模型调用，生成恰好 3 个不同的推荐问题。默认启用，可用 `sprout.agent.enable-recommendations: false` 关闭；推荐仅用于展示和持久化，不进入后续对话的推理记忆。


启动时，`schema-mysql.sql` 在 MySQL 中创建对话表、回合明细表及 `uploaded_files` 文件元数据表；旧库启动时自动补齐文件表、用户消息的 `file_id`、`file_name` 列以及可空的 `recommendations_json` 列。回合明细按助手消息 ID 保存思考片段、工具结果、来源对象、推荐问题以及响应耗时；删除对话时由外键级联清理聊天记录，但不删除独立上传的文件和 MinIO 对象（上传后可用于多轮对话）。旧版仅含 URL 的来源数据仍能展示。思考内容、工具过程、来源及推荐不进入会话记忆。

当前还没有用户登录和对话隔离：可访问此应用的访客都能查看或删除历史对话。仅限可信内网使用；公开部署前需加入身份认证与按用户授权。

## 代码结构

`controller` 只处理 HTTP/SSE 和请求校验；`agents/BaseAgent` 承载两种 Agent 共用的流式 ReAct、会话记忆、持久化与停止流程，`agents/WebSearchReactAgent` 处理联网聊天，`agents/FileReactAgent` 负责文件上下文构造与图片视觉识别；`UploadedFileService` 负责解析文件并存取 MySQL/MinIO；`AgentTaskManager` 按会话管理两种 Agent 共用的运行任务；`entity` 映射 MySQL 表，`mapper` 承担数据访问；`config` 管理 MySQL、pgvector、MinIO 和 Tavily MCP 客户端。各层位于 `src/main/java/com/sprout/agent/`。

`prompts` 包保存 Agent 提示词。联网聊天使用 `ReactAgentPrompts.getWebSearchPrompt()`，文件聊天使用 `ReactAgentPrompts.getFilePrompt()` 作为每次请求的系统消息；文件内容是用户提供的参考资料，不作为系统指令。研究和 PPT 提示词是对应功能的模板，目前未接入聊天执行链。

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
