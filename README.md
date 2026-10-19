# ai-kb-agent

一个**可以直接上线**的知识库 Agent：文档上传 → 结构感知切分 → 向量化入库 → 混合检索 → 带引用的流式回答。
附带一套对外门户站点与内容管理后台，适合给实验室、团队或企业内部做资料问答。

## 特性

- **结构感知切分**：先按 Markdown 标题 / 编号切成章节，再对每章做带重叠的定长切分，片段里保留章节名，引用能精确到"哪个文件的哪一节"。
- **混合检索**：关键词召回 + 向量召回双路并行，用 RRF 融合后接交叉编码器精排，补上纯向量检索在专有名词、编号上的短板。
- **引用溯源**：回答强制标注 `[1][2]` 角标，前端同步展示原文片段，结论可逐条回溯。
- **防幻觉**：提示词强约束 + 检索相似度阈值 + 资料不足时明确回答"无法确定"。
- **流式输出**：SSE 逐字推送，前端流式渲染 Markdown，未闭合的标记会被临时扣住，不会闪出 `**`。
- **内容管理**：站点文案、团队成员、论文成果都能在后台可视化编辑；知识库按内容哈希增量同步，重复导入自动跳过。
- **可替换的技术选型**：对话模型、向量模型、向量库互相独立，换供应商只改配置。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 17 · Spring Boot 3.5 · LangChain4j 1.21 |
| 存储 | PostgreSQL 17 + pgvector · Redis |
| 前端 | Vue 3 · Vite · Element Plus |
| 部署 | Docker Compose · Nginx（可选） |

## 架构

```
浏览器（Vue 3，SSE 流式）
   │
Spring Boot
   ├─ 入库：解析 PDF/DOCX/MD/TXT → 章节切分 → 向量化 → 落库
   ├─ 检索：向量召回 + 关键词召回 → RRF 融合 → 重排
   └─ 生成：拼提示词 → 流式输出（带引用编号）
   │
PostgreSQL + pgvector        Redis（问题向量缓存、限流）
```

## 快速开始

### 环境要求

- JDK 17 或以上
- Maven 3.8+（仓库自带 `mvnw` / `mvnw.cmd`，不装 Maven 也能用）
- Docker（只有"完整模式"需要）

### 准备 API Key

对话模型和向量模型是分开的：**DeepSeek 只提供对话接口，没有 embedding**，所以向量模型要另选一家。

```bash
cp .env.example .env      # Windows: copy .env.example .env
```

编辑 `.env`，至少填这两个：

```
KB_CHAT_API_KEY=你的对话模型key      # 默认 DeepSeek
KB_EMBED_API_KEY=你的向量模型key     # 默认硅基流动 bge-m3，有免费额度
```

### 方式一：30 秒跑起来（内存模式，不需要数据库）

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

打开 <http://localhost:8080>。`dev` 模式用内存向量库和内存元数据，**重启即清空**，适合先看效果。

> 必须在项目根目录启动——`.env` 是按相对路径加载的，换目录会读不到密钥。

### 方式二：完整模式（Postgres + Redis）

```bash
docker compose up -d
mvn spring-boot:run
```

首次启动会自动建表，并写入一份**示例数据**（虚构的成员与论文），你可以直接看到完整效果。

### 方式三：Docker 部署（服务器）

```bash
# 1. 打包（镜像里只有 JRE，jar 需要在本机打好）
mvn -DskipTests package

# 2. 准备服务器上的密钥文件
cp deploy/.env.server.example deploy/.env
# 编辑 deploy/.env：数据库密码、Redis 密码、后台口令、两个 API Key

# 3. 构建并启动
cd deploy && bash deploy.sh
```

启动后访问 `http://<你的服务器IP>:8080`。

## 配置说明

所有配置都支持环境变量覆盖，本地开发默认读根目录的 `.env`。

| 变量 | 说明 | 默认 |
|---|---|---|
| `KB_CHAT_BASE_URL` / `KB_CHAT_API_KEY` / `KB_CHAT_MODEL` | 对话模型 | DeepSeek / `deepseek-flash` |
| `KB_EMBED_BASE_URL` / `KB_EMBED_API_KEY` / `KB_EMBED_MODEL` | 向量模型 | 硅基流动 / `BAAI/bge-m3` |
| `KB_EMBED_DIMENSION` | 向量维度，必须与模型一致 | `1024` |
| `KB_DB_*` | PostgreSQL 连接 | `localhost:5432/kb` |
| `KB_REDIS_*` | Redis 连接 | `localhost:6379` |
| `KB_ACCESS_TOKEN` | 访客口令，**留空表示站点完全公开** | 空 |
| `KB_ADMIN_TOKEN` | 后台口令，保护 `/api/admin/**` 与文档管理 | 空（本地免密） |
| `KB_APP_PORT` | 对外端口 | `8080` |
| `JAVA_OPTS` | JVM 参数 | `-Xms256m -Xmx1g` |

> 部署到公网时，**`KB_ADMIN_TOKEN` 一定要设**，否则任何人都能改你的站点内容。

## 接口

访客接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat` | 同步问答，返回答案 + 引用来源 |
| GET | `/api/chat/stream?question=` | SSE 流式问答，事件依次为 `sources` / `delta` / `done` / `error` |
| GET | `/api/lab/content` | 站点文本 |
| GET | `/api/lab/members` | 团队成员 |
| GET | `/api/lab/publications` | 论文成果 |

管理员接口（需要 `X-Admin-Token` 请求头或 `kb_admin_token` Cookie）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/documents` | 上传文档（form-data，字段名 `file`） |
| GET | `/api/documents` | 文档列表与入库状态 |
| DELETE | `/api/documents/{id}` | 删除文档，同时清理向量 |
| PUT | `/api/admin/lab/content` | 批量保存站点文本 |
| POST | `/api/admin/lab/members` | 新增 / 修改成员 |
| POST | `/api/eval/ingest-docs` | 把 `knowledge/` 目录同步进知识库 |
| POST | `/api/eval/run` | 跑检索消融实验 |

## 目录结构

```
src/main/java/com/kbagent/
├── config/     模型与向量库装配、配置项
├── document/   文档元数据与片段存储（Postgres / 内存两套实现）
├── ingest/     解析 → 章节切分 → 向量化 → 落库
├── retrieval/  向量召回、关键词召回、RRF 融合、重排
├── agent/      提示词拼装与生成
├── lab/        门户站点：内容、成员、成果、管理接口
├── eval/       评测集生成与消融实验
└── web/        REST + SSE 接口、口令校验、限流

frontend/       Vue 3 门户与后台（构建产物输出到 src/main/resources/static）
knowledge/      知识库语料（示例数据）
eval/           评测集
deploy/         Dockerfile、生产 compose、部署脚本
docs/           设计与技术文档
```

## 关于示例数据

仓库里带的成员、论文、站点文案都是**虚构的演示数据**，用于 clone 下来就能看到完整效果：

- 首次启动时由 `LabDataSeeder` 写入（仅当数据库为空）
- 启动后进 `/#/admin` 可以逐项改成你自己的内容
- `knowledge/` 目录里的语料同理，改完在后台点「同步知识库目录」

## 常见问题

**启动报 `Unsupported class version`**
说明 PATH 里的 `java` 不是 17。检查 `java -version`，或显式指定 JAVA_HOME。

**启动报 `Unable to establish loopback connection`**
运行环境禁止了本地回环 socket（受限沙箱、部分安全软件）。换到普通终端运行即可，不是代码问题。

**一直提示"无法确定"或答非所问**
多半是知识库为空。进后台点「同步知识库目录」，或确认 `knowledge/` 里有 Markdown 文件。

**报向量维度不匹配**
`KB_EMBED_DIMENSION` 必须等于向量模型的实际输出维度（`bge-m3` 是 1024）。改了维度要先删掉 `kb_embedding` 表再重建。

**线上页面能开、接口 401**
说明设置了 `KB_ACCESS_TOKEN`，而前端没有访客口令输入入口。公开站点请把它留空。

## 许可证

尚未指定。如果要开源，建议补一个 `LICENSE`（MIT 或 Apache-2.0）。
