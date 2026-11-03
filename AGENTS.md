# Repository Guidelines

## Project Overview

萌芽 (`sprout_agent`) is a Chinese-language, Doubao-style AI chat assistant. It uses DeepSeek for chat and Tavily MCP for web search; MySQL stores conversations, while PostgreSQL/pgvector is a separate vector store. The browser client is served directly by Spring Boot.

## Architecture & Data Flow

- `controller` validates HTTP and exposes SSE; `agents/BaseAgent` runs chat/file ReAct flows, while `agents/ppt/PptBuilderAgent` runs a separate PPT state machine. `service` owns conversation transactions/task lifecycle, `mapper` persists to MySQL. `config` wires MySQL, pgvector, MinIO, Tavily MCP.
- `POST /api/chat` → `WebSearchReactAgent`: rebuild per-request `MessageWindowChatMemory` from MySQL, stream DeepSeek, merge tool-call arguments, execute Tavily plus `HostFileTools` and `HostBashTool` callbacks on `boundedElastic`, and feed results to the next round. `POST /api/file-chat` → `FileReactAgent` retains attachment-only tools. `sprout.agent.max-rounds` bounds tool rounds.
- `POST /api/skills/chat` → `agents/skills/SkillsReactAgent`: same `BaseAgent` memory/SSE/tool loop as ordinary chat, with extra skill installation and isolated Python tools; PPTX tasks must execute and validate the final file, not deliver only a script. Skill chat has a 24-round tool budget (`sprout.agent.skills.max-rounds`) plus up to six additional rounds for failed tool calls; tool failures return their actual command output to the model, which evaluates a corrected command, a fallback, or an honest stop. The final round still executes tool calls and then requests a tool-free final response. Ordinary and skill chat both expose host file tools (`read_file`, `write_file`, `edit_file`, `list_files`, `glob_files`, `grep`) and `bash`, which runs `/bin/bash -lc` on the host as the service OS user (120s timeout, 100 KB output). `run_python` and `run_skill_script` remain Docker-isolated; file chat does not get host tools. This is unauthenticated host command and file access: deploy only on a network restricted to trusted users; public deployment requires authentication.
- Skills chat compacts its in-flight message list before every model round via `context/ContextCompactor` (two layers: JSON-placeholder replacement of old tool responses/args beyond `sprout.agent.skills.context.{keep-recent-tools,max-tool-length}`, `read_skill` always protected; then an LLM structured summary replacing all old messages when the CJK/EN token estimate exceeds `token-threshold`, falling back to keeping the last 10 messages on summary failure). Compaction only mutates the per-turn runtime list, never MySQL history; ordinary chat/file agents opt out (null policy).
- `POST /api/research` → `agents/deepResearch/DeepResearchAgent`: clarify or pause, generate topic and bounded search plan, execute Tavily MCP searches by ordered groups (up to three parallel), critique and replan for bounded rounds, compress long working context, stream a source-backed report. Research progress and final answer persist in `ConversationService`; the UI restores research mode from the saved thinking prefix.
- `POST /api/ppt` → `PptBuilderAgent`: `INIT → REQUIREMENT → SEARCH → TEMPLATE → OUTLINE → SCHEMA → RENDER → SUCCESS`. Stages checkpoint to `ai_ppt_inst`; failed stage saved for resume. `SchemaStrategy` validates JSON against actual template shape names/types and checkpoints generated images' MinIO URLs; `RenderStrategy` calls the Python renderer and stores `.pptx` in MinIO. `GET /api/ppt/conversations/{id}/download` downloads completed files.
- `AgentTaskManager` uses Redis SETNX leases per conversation plus a local task map. Every instance subscribes to the Redis stop channel; a stop request on another instance is published to the owner. Leases expire after 30 minutes and renew every 5 minutes; compare-and-delete/renew use the unique task token, so stale tasks cannot release replacements. `ConversationService` saves completed exchanges; interrupted chat/research saves partial answer, PPT retains its latest stage checkpoint. `static/app.js` consumes SSE (`conversation`, `reasoning`, `thinking`, `delta`, `reference`, `recommend`, `error`, `done`); preserve SSE/history parity.
- Skills-only phased SSE adds `tool_start`/`tool_end` (tool name, call ID, completion status only), `stage_error` (retry notice), and `complete` before successful `done`; legacy agents keep their existing events. Retries are limited to three with a 10-second delay, only before answer text is emitted. The Skills timeline is persisted alongside tool records in `chat_turn_details.tools_json` and exposed as `MessageView.timeline`; arguments/results never enter stage SSE or history JSON. `static/app.js` renders an accessible collapsible timeline, restores it from history, and keeps failures expanded.

## Key Directories

- `src/main/java/com/sprout/agent/agents/ppt/`: PPT state machine, persisted instance + intent, strategies, actual template catalog, `PptImageService`, `PptRenderService` and image download endpoint. `tool/FileContentTool.java` handles attachment retrieval; MCP callbacks live in `config/TavilyMcpConfiguration.java`.
- `src/main/java/com/sprout/agent/agents/deepResearch/`: research orchestration and tool-backed evidence collection. `prompts/PlanExecutePrompts.java` holds clarification, planning, execution, critique, compression and report prompts.
- `skills/`: trusted, writable skill directory (`sprout.agent.skills-directory` for absolute paths; shared across instances). `source-check/SKILL.md` shows the convention; ZIP-installed skills may ship `scripts/*.py` runnable only via the sandboxed tool. Any visitor can install/run — trusted intranet only.
- `agent-workspace/`: writable, persistent Skills Agent work area; do not store credentials. In workspace-only mode this is the file tool root; in authenticated host mode only sandboxed `bash` sees this directory, while host file tools use absolute paths. `run_python` has no host mount and `run_skill_script` mounts installed skills read-only. Multi-instance deployments need the same host token and shared workspace for shell artifacts.
- `src/main/resources/static/`: `index.html`, `app.js`, `styles.css`; no frontend build pipeline. Render assistant Markdown through DOMPurify, keep user input as text, and accept only HTTP(S) links.
- `src/main/resources/`: `application.yml` imports local `.env` and Nacos YAML; `schema-mysql.sql` defines conversation/PPT tables. Startup migrations live in `config/DatabaseConfiguration.java`; `templates/ai.pptx` supplies actual slide shape names.
- `src/test/java/com/sprout/agent/`: JUnit unit tests for agents, services and prompts.

## Development Commands

```sh
mvn test                              # isolated unit tests
mvn package                           # build application jar
./run.sh                              # from repository root; wraps mvn spring-boot:run
python3 -m pip install -r requirements.txt  # optional PPT Python dependency
```

`docker compose up -d` starts local MySQL and pgvector; `docker compose --profile minio up -d` also starts MinIO. Redis is shared across instances on the ZeroTier network; its host, port, password and database 1 are configured only in Nacos `sprout-agent-zerotier.yaml` (`spring.data.redis`). Do not add Redis credentials to tracked files or the local `.env`. The app normally relies on reachable ZeroTier/Nacos services; opening `http://localhost:8080` checks the running UI.

## Code Conventions & Common Patterns

- Java 21, `com.sprout.agent` packages, constructor injection, records for DTOs/request bodies, Bean Validation on HTTP inputs, and `ResponseStatusException` for API failures. Conversation IDs are 32 lowercase hex characters; do not change existing API paths or table names casually.
- Keep controllers thin; implement ReAct scheduling, tool error responses, and successful-turn persistence in services. Spring AI automatic tool execution is disabled intentionally; blocking MCP/DB/model work runs off the reactive event loop on `Schedulers.boundedElastic`.
- UI uses plain DOM and module-level state; preserve stale-request guards (`viewToken`, `historyRequest`) and sanitize model-provided HTML. User-facing copy and GitHub commit messages should be Chinese.
- Preserve the MySQL-primary / pgvector-secondary datasource split. New persisted fields require matching schema, startup migration for existing installations, entity/mapper mapping, and history serialization.

## Important Files

- `pom.xml`: Maven versions/dependencies; `run.sh`: application launcher.
- `src/main/java/com/sprout/agent/SproutAgentApplication.java`: Spring Boot entry point; `agents/BaseAgent.java`, `agents/skills/SkillsReactAgent.java`, `agents/ppt/PptBuilderAgent.java`, `agents/deepResearch/DeepResearchAgent.java`: distinct chat/file, skills, PPT and research orchestration; `service/ConversationService.java` and `agents/ppt/PptInstService.java`: durable history/checkpoints.
- `config/DatabaseConfiguration.java`, `config/TavilyMcpConfiguration.java`, `src/main/resources/application.yml`, `src/main/resources/schema-mysql.sql`: integration and data configuration.
- `README.md`: API event contract, configuration names, and deployment details.

## Runtime/Tooling Preferences

- Use JDK 21 and Maven; there is no Node/Bun package manager or frontend compile step. Reactor 3.7.11 and Spring AI 1.1.0 are managed by `pom.xml`.
- Run from the repository root: Spring imports git-ignored `.env` as properties, with OS environment variables taking precedence. Nacos namespace `sprout_agent`, Data ID `sprout-agent-zerotier.yaml` supplies shared YAML including Redis; restart after changes. Keep `.env`, API keys and exported Nacos secrets out of Git. PPT images need a separate configured OpenAI-compatible ImageModel (`spring.ai.openai.image.{base-url,api-key,options.model}`) and `sprout.ppt.image.public-base-url` reachable by the renderer; absent credentials/URL cause SCHEMA failure but the JSON checkpoint survives.
- No repository lint/formatter or coverage command is configured; follow neighboring Java/JS formatting rather than adding tooling implicitly.

## Testing & QA

- `mvn test` runs JUnit 5/Mockito unit tests without contacting MySQL, Redis, Nacos, Tavily, or MinIO. Examples: `service/AgentTaskManagerTest.java`, `agents/ppt/PptBuilderAgentTest.java`, `service/ConversationServiceTest.java`.
- Add behavioral tests for changes to memory windows, tool-call fragment merging, search-result parsing, persistence, and archive rules; mock mappers and exercise pure helpers rather than external services. There is no configured coverage threshold or integration-test suite.
- For UI or SSE changes, also smoke-test the real browser interaction or running API; unit tests alone do not establish streaming/rendering behavior. Avoid exposing credentials in test output.
