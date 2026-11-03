# 萌芽（sprout_agent）

基于 Spring Boot 的豆包风格 AI 助手，提供联网聊天、文件问答、PPT 生成、深度研究和技能助手五种能力。对话使用 DeepSeek，联网搜索使用 Tavily MCP。

## 技术栈

| 类别 | 组件 |
|---|---|
| 语言/框架 | Java 21、Spring Boot 3.5.6、Spring AI 1.1.0、Reactor 3.7.11 |
| 模型 | DeepSeek（对话）、Tavily MCP（搜索）、`qwen3-embedding:4b`（向量）、`qwen-image-2.1-turbo`（PPT 配图，阿里百炼） |
| 存储 | MySQL 8 + MyBatis-Plus 3.5.5（主库）、PostgreSQL + pgvector（长文档向量）、MinIO（文件/图片）、Redis（多实例任务互斥与停止广播） |
| 注册/配置中心 | Nacos（命名空间 `sprout_agent`） |
| 文档/渲染 | Apache PDFBox 2.0.30、Apache POI 5.2.5、Python `python-pptx` 1.0.2 |
| 前端 | 原生 HTML/JS/CSS，无构建流程 |

## 功能

- **联网聊天**（`POST /api/chat`）：流式 ReAct，Tavily 搜索 + 宿主机文件工具/`bash`，带推理过程、参考来源和推荐问题。
- **文件问答**（`POST /api/file-chat`）：上传 PDF/DOCX/TXT/MD/图片（≤10 MiB），短文档直读、长文档 pgvector 检索、图片视觉模型识别后回答。
- **PPT 生成**（`POST /api/ppt`）：需求澄清 → 搜索 → 模板 → 大纲 → Schema → 文生图配图 → Python 渲染 `.pptx` 存 MinIO，可下载；失败可从断点恢复。
- **深度研究**（`POST /api/research`）：生成检索计划，分组并行 Tavily 搜索、评估重规划、上下文压缩，输出带引用来源的研究报告。
- **技能助手**（`POST /api/skills/chat`）：`@技能名` 加载已安装技能，支持从 GitHub/直链/ZIP 安装技能；提供 Python 执行（Docker 隔离）、宿主机文件工具、`bash`，工具调用以时间线展示，失败可恢复重试。
- **通用**：会话历史持久化与恢复、归档/重命名/搜索、停止生成（跨实例）、部分回答保存、两层上下文压缩（长链路工具任务自动摘要）。

## 配置

凭证不提交仓库：本机 `.env`（已被 `.gitignore` 排除）保存 DeepSeek/数据库等凭证；Redis、Tavily Key 等共享配置放在 Nacos `sprout-agent-zerotier.yaml`（Data ID），修改后重启生效。操作系统环境变量优先于 `.env`。

**`.env` 主要变量**：

| 变量 | 说明 |
|---|---|
| `SPRING_AI_OPENAI_API_KEY` | DeepSeek API Key |
| `MYSQL_URL` / `PGVECTOR_URL` / `MINIO_ENDPOINT` | 存储地址及账号密码 |
| `SPRING_AI_OPENAI_IMAGE_BASE_URL` / `..._API_KEY` / `..._OPTIONS_MODEL` | PPT 文生图（兼容 OpenAI 接口，末尾斜杠必需） |
| `SPROUT_PPT_IMAGE_PUBLIC_BASE_URL` | 渲染器可访问的服务地址 |
| `SPROUT_VISION_BASE_URL` / `SPROUT_VISION_MODEL` | 视觉模型（可选 `SPROUT_VISION_API_KEY`） |

**Nacos**：地址 `10.244.0.111:8848`，命名空间 `sprout_agent`，含 Redis（`10.244.0.111:6379` db1）、Tavily Key 等。

**常用配置键**（`application.yml`，均可被环境变量覆盖）：`sprout.agent.max-rounds`（聊天工具轮数）、`sprout.agent.skills.max-rounds`（技能 24 轮 + 6 轮失败恢复）、`sprout.agent.skills.context.{token-threshold,keep-recent-tools,max-tool-length}`（上下文压缩）、`sprout.research.max-rounds`、`sprout.agent.enable-recommendations`。

## 运行

```sh
# 1. 准备：JDK 21、Maven；远端 MySQL/pgvector 建好 sprout_agent 库；
#    Docker 拉取 python:3.12-alpine（技能 Python 执行）
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt  # PPT 渲染依赖

# 2. 启动（读取根目录 .env）
./run.sh

# 3. 访问
open http://localhost:8080
```

服务通过 Nacos Discovery 注册为 `sprout-agent`。Nginx 转发 SSE 需关闭 `proxy_buffering` 并调大 `proxy_read_timeout`。

## 主要接口

| 接口 | 说明 |
|---|---|
| `POST /api/chat` / `/api/file-chat` / `/api/ppt` / `/api/research` / `/api/skills/chat` | 均返回 SSE 流；请求体 `{"conversationId":null,"message":"…"}`
| `POST /api/files` | 上传文件，返回 `fileId`
| `GET /api/skills` | 已安装技能列表 |
| `GET /api/conversations[?archived=&query=]` | 最近 50 条对话 |
| `GET/DELETE /api/conversations/{id}` | 对话详情（含推理/来源/时间线）/ 删除 |
| `PATCH /api/conversations/{id}/{archive,restore,title}` | 归档、恢复、重命名 |
| `PATCH /api/chat/{conversationId}/stop` | 停止生成 |
| `GET /api/ppt/conversations/{id}` / `.../download` | PPT 状态 / 下载 |

## 代码结构

```
src/main/java/com/sprout/agent/
├── controller/    HTTP/SSE 入口
├── agents/        BaseAgent（聊天/文件共用 ReAct）、skills/、ppt/、deepResearch/
├── context/       两层上下文压缩（ContextCompactor/ContextPolicy/TokenEstimator）
├── service/       会话持久化、任务管理（Redis 租约）、文件/向量服务
├── config/        数据源、Tavily MCP、Redis 广播
└── tool/          宿主机文件/bash 工具
```

前端在 `src/main/resources/static/`。测试：`mvn test`。

## 安全边界

应用无登录认证，且聊天提供宿主机命令执行与文件读写能力。**仅限可信内网部署**；公开部署前必须增加身份认证与对话隔离。上传文件不随对话删除自动清理。
