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

打开 <http://localhost:8080>。聊天 API 为 `POST /api/chat`，请求体 `{"message":"你好"}`。

每条对话和消息保存在 MySQL；页面刷新后可从历史列表继续查看。首次发送时创建对话，后续请求携带 `conversationId`；“清空对话”会删除当前对话及其消息。

- `GET /api/conversations`：最近 50 条对话
- `GET /api/conversations/{id}`：对话及消息
- `DELETE /api/conversations/{id}`：删除对话
- `POST /api/chat`：`{"conversationId":null,"message":"你好"}`；新对话传 `null`，继续对话传已有 ID

启动时，`schema-mysql.sql` 会在 MySQL 中幂等创建对话表。

当前还没有用户登录和对话隔离：可访问此应用的访客都能查看或删除历史对话。仅限可信内网使用；公开部署前需加入身份认证与按用户授权。

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
