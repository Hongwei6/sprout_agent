# 萌芽（sprout_agent）

萌芽是基于 Spring Boot 的豆包风格 AI 助手项目，前端由 Spring Boot 静态资源服务，聊天接口使用 Spring AI 对接火山引擎豆包的 OpenAI 兼容 API。

## 技术栈

- Java 17、Spring Boot 3.5.6、Spring AI 1.1.0
- MySQL 8、MyBatis-Plus 3.5.5
- PostgreSQL + pgvector、MinIO
- Apache PDFBox 2.0.30、Apache POI 5.2.5、Python `python-pptx` 1.0.2
- Reactor 3.7.11

## 本地运行

1. 启动本地 MySQL 8 和 pgvector：

   ```sh
   docker compose up -d mysql pgvector
   ```

2. 配置豆包火山方舟凭证和模型接入点：

   ```sh
   export DOUBAO_API_KEY=你的火山方舟API密钥
   export DOUBAO_MODEL=你的Endpoint_ID
   ```

3. 启动应用：

   ```sh
   mvn spring-boot:run
   ```

打开 <http://localhost:8080>。聊天 API 为 `POST /api/chat`，请求体 `{"message":"你好"}`。未配置模型密钥时接口返回 `503`，不会生成伪造回复。

## ZeroTier 服务配置

`zerotier` Spring Profile 已按 `/Users/hongwei/dev/workspace/serve` 中的记录配置：

- MySQL：T480s `10.244.0.111:3306`
- pgvector：T480s `10.244.0.111:5433`
- MinIO：Debian `10.244.0.102:9000`

启用前，需在远端准备独立的 `sprout_agent` MySQL/PostgreSQL 数据库与账号；项目不复用服务器上其他应用的数据库。凭证通过环境变量注入，不存入仓库：

```sh
export SPRING_PROFILES_ACTIVE=zerotier
export MYSQL_USER=...
export MYSQL_PASSWORD=...
export PGVECTOR_USER=...
export PGVECTOR_PASSWORD=...
export MINIO_ACCESS_KEY=...
export MINIO_SECRET_KEY=...
export DOUBAO_API_KEY=...
export DOUBAO_MODEL=...
mvn spring-boot:run
```

环境变量也可覆盖连接地址：`MYSQL_URL`、`PGVECTOR_URL`、`MINIO_ENDPOINT`；pgvector 默认维度为 1536，如使用其他 embedding 模型需设置匹配的 `PGVECTOR_DIMENSIONS`。

`python-pptx` 是 Python 文档处理依赖，通过 `python3 -m pip install -r requirements.txt` 安装；其余依赖由 Maven 管理。MinIO 的本地容器可选启用：`docker compose --profile minio up -d minio`。
