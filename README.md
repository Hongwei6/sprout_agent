# 萌芽（sprout_agent）

萌芽是基于 Spring Boot 的豆包风格 AI 助手项目，前端由 Spring Boot 静态资源服务，聊天接口使用 Spring AI 对接火山引擎豆包的 OpenAI 兼容 API。

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

`.env` 中 `DOUBAO_API_KEY` 与 `DOUBAO_MODEL` 需填入火山方舟凭证和 Endpoint ID 后，聊天才会调用模型。尚未配置时 API 返回 503，不生成伪造回复。

## 其他

本地 MySQL 和 pgvector 可用 `docker compose up -d mysql pgvector` 启动；MinIO 本地容器可选：`docker compose --profile minio up -d minio`。

`python-pptx` 是 Python 文档处理依赖，通过 `python3 -m pip install -r requirements.txt` 安装；其余依赖由 Maven 管理。pgvector 默认维度为 1536，切换 embedding 模型时需设置匹配的 `PGVECTOR_DIMENSIONS`。
