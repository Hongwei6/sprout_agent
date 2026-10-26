# 萌芽（sprout_agent）

萌芽是基于 Spring Boot 的豆包风格 AI 助手。对话使用 DeepSeek；文本向量模型与视觉模型通过 `.env` 中的 OpenAI 兼容服务配置。

## 技术栈

- Java 17、Spring Boot 3.5.6、Spring AI 1.1.0
- MySQL 8、MyBatis-Plus 3.5.5
- PostgreSQL + pgvector、MinIO
- Apache PDFBox 2.0.30、Apache POI 5.2.5、Python `python-pptx` 1.0.2
- Reactor 3.7.11

## 运行

项目根目录下的 `.env` 保存 ZeroTier 服务凭证，并被 `.gitignore` 排除。启动脚本自动加载该文件并启用 `zerotier` Profile：

```sh
./run.sh
```

打开 <http://localhost:8080>。聊天 API 为 `POST /api/chat`，请求体 `{"message":"你好"}`。

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
