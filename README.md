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
Tavily API Key 存于 `sprout_agent` 命名空间的 Nacos 配置 `sprout-agent-zerotier.yaml`，字段为 `sprout.tavily.api-key`。聊天时模型可按需调用联网搜索工具；结果包含标题、链接和摘要。修改该配置后重启应用生效。Nacos 中的 API Key 同样是敏感凭证，应限制配置读取权限，不要将配置导出到公开仓库。

服务启动后通过 Nacos Discovery 注册为 `sprout-agent`，在 Nacos 服务中心切换到 `sprout_agent` 命名空间查看。优先注册本机 `10.244.*` ZeroTier 地址；停止服务后实例会自动注销。

打开 <http://localhost:8080>。聊天 API 为 `POST /api/chat`，请求体 `{"message":"你好"}`，响应为 `text/event-stream`（SSE）。

每次请求根据 `conversationId` 从 MySQL 读取最近已完成的问答，重建本次请求专属的 `MessageWindowChatMemory`；运行期消息列表单独承载当前问题、流式模型输出和工具调用结果。只在成功生成最终答案后，于同一数据库事务保存当前问题、最终回答及回合明细；中断或失败的请求不写入问答。重启或多实例请求可从数据库恢复历史，历史页面仍展示完整问答；工具调用中间消息不注入下次模型上下文。

- `GET /api/conversations`：最近 50 条对话
- `GET /api/conversations/{id}`：对话及消息
- `DELETE /api/conversations/{id}`：删除对话
- `POST /api/chat`：`{"conversationId":null,"message":"你好"}`；新对话传 `null`，继续对话传已有 ID。SSE 事件依次包含 `conversation`（ID）、`delta`（增量文本）、`done`（完成）；异常发送 `error`。客户端应按事件逐段拼接文本。

聊天使用流式 ReAct 轮次：识别工具调用、合并同一调用的参数分片、执行 Tavily 并将工具结果（包括失败信息）反馈给下一轮。无工具调用即结束；超过 `sprout.agent.max-rounds` 后不再提供工具，强制生成最终回答。默认最多 5 轮；设置为 0 表示不限制轮次。`sprout.agent.max-messages` 控制每次注入模型的历史消息数，必须为至少 2 的偶数；Nacos YAML 配置为 20（至多最近 10 轮完整问答），只限制推理窗口，不删除 MySQL 的完整历史。

启动时，`schema-mysql.sql` 在 MySQL 中幂等创建对话表和 `chat_turn_details` 回合明细表。回合明细以助手消息 ID 关联，保存模型实际返回的思考片段（未返回时为 `NULL`）、工具调用与结果 JSON、Tavily 来源链接 JSON，以及首次响应和总耗时（毫秒）；删除对话时由外键级联清理。思考内容、工具过程和来源不进入会话记忆。

当前还没有用户登录和对话隔离：可访问此应用的访客都能查看或删除历史对话。仅限可信内网使用；公开部署前需加入身份认证与按用户授权。

## 代码结构

`controller` 只处理 HTTP/SSE 和请求校验；`service` 负责 ReAct 轮次与会话事务；`entity` 映射 MySQL 表，`mapper` 承担数据访问；`tool` 封装 Tavily，`config` 管理 MySQL、pgvector 和 MinIO。各层位于 `src/main/java/com/sprout/agent/`，API 路径和表名保持不变。

## ZeroTier 服务

配置来源：`/Users/hongwei/dev/workspace/serve` 中的服务器文档及项目根目录 `.env`。

- MySQL：T480s `10.244.0.111:3306`
- pgvector：T480s `10.244.0.111:5433`
- MinIO：Debian `10.244.0.102:9000`

启动前需在远端准备独立的 `sprout_agent` MySQL/PostgreSQL 数据库；项目不复用服务器上其他应用的数据。远端服务凭证保存在本地 `.env`，不要提交或复制到公开日志。

`.env` 中的 DeepSeek API Key 和 `deepseek-flash` 模型已由 Spring AI 加载。向量模型为 `qwen3-embedding:4b`（1024 维）；视觉模型地址和模型名为 `SPROUT_VISION_BASE_URL`、`SPROUT_VISION_MODEL`。

## 其他

应用默认连接上述 ZeroTier 地址。若使用本地 Docker 服务，需显式设置 `MYSQL_URL`、`PGVECTOR_URL`、`MINIO_ENDPOINT` 及对应本地账号密码后再运行。

`python-pptx` 是 Python 文档处理依赖，通过 `python3 -m pip install -r requirements.txt` 安装；其余依赖由 Maven 管理。pgvector 维度由 `PGVECTOR_DIMENSIONS` 配置，必须与 embedding 模型的维度一致。
