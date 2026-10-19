-- 文档元数据表：记录每份文档的入库状态，方便查问题和做重试
CREATE TABLE IF NOT EXISTS kb_document (
    id            UUID PRIMARY KEY,
    file_name     TEXT        NOT NULL,
    content_type  TEXT,
    size_bytes    BIGINT      NOT NULL DEFAULT 0,
    status        TEXT        NOT NULL,
    chunk_count   INT         NOT NULL DEFAULT 0,
    error_message TEXT,
    stage         TEXT,
    progress      INT         NOT NULL DEFAULT 0,
    content_hash  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 异步入库改造时新增的两列。已有数据库靠这两条 ALTER 平滑升级，不需要手工迁移
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS stage TEXT;
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS progress INT NOT NULL DEFAULT 0;
-- 内容哈希：用来判断"这份文件是不是已经入库过了"，避免重复导入产生重复片段
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS content_hash TEXT;
CREATE INDEX IF NOT EXISTS idx_kb_document_content_hash ON kb_document (content_hash);

-- 片段表：id 与向量库中的记录一一对应；第二周做关键词召回时直接查这张表
CREATE TABLE IF NOT EXISTS kb_chunk (
    id          TEXT PRIMARY KEY,
    document_id UUID        NOT NULL REFERENCES kb_document (id) ON DELETE CASCADE,
    chunk_index INT         NOT NULL,
    section     TEXT,
    content     TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_kb_chunk_document_id ON kb_chunk (document_id);
CREATE INDEX IF NOT EXISTS idx_kb_document_created_at ON kb_document (created_at DESC);

-- ============ 实验室门户：内容管理 ============

-- 团队成员。结构化数据，字段固定，所以老老实实建列
CREATE TABLE IF NOT EXISTS lab_member (
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL,
    role       TEXT,
    title      TEXT,
    photo_path TEXT,
    bio        TEXT,
    email      TEXT,
    sort_order INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 站点文本内容。键值结构，加字段不用改表结构，代价是弱类型
CREATE TABLE IF NOT EXISTS lab_content (
    key        TEXT PRIMARY KEY,
    value      TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 论文成果。结构化列表
CREATE TABLE IF NOT EXISTS lab_publication (
    id         UUID PRIMARY KEY,
    title      TEXT        NOT NULL,
    authors    TEXT,
    venue      TEXT,
    year       INT,
    url        TEXT,
    sort_order INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 问答日志：记录访客问了什么、系统答得怎么样。
-- 这张表是"数据驱动迭代"的闭环入口——从真实问题里发现知识库的缺口。
CREATE TABLE IF NOT EXISTS qa_log (
    id             UUID PRIMARY KEY,
    question       TEXT        NOT NULL,
    retrieved      INT         NOT NULL DEFAULT 0,
    answered       BOOLEAN     NOT NULL DEFAULT TRUE,
    first_token_ms INT,
    total_ms       INT,
    client_ip      TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_qa_log_created_at ON qa_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_lab_member_sort ON lab_member (sort_order);

-- ============ 实验室门户：内容管理 ============

-- 团队成员。结构化数据，字段固定，所以老老实实建列
CREATE TABLE IF NOT EXISTS lab_member (
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL,
    role       TEXT,
    title      TEXT,
    photo_path TEXT,
    bio        TEXT,
    email      TEXT,
    sort_order INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 站点文本内容。键值结构，加字段不用改表结构，代价是弱类型
CREATE TABLE IF NOT EXISTS lab_content (
    key        TEXT PRIMARY KEY,
    value      TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 论文成果。结构化列表
CREATE TABLE IF NOT EXISTS lab_publication (
    id         UUID PRIMARY KEY,
    title      TEXT        NOT NULL,
    authors    TEXT,
    venue      TEXT,
    year       INT,
    url        TEXT,
    sort_order INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 问答日志：记录访客问了什么、系统答得怎么样。
-- 这张表是"数据驱动迭代"的闭环入口——从真实问题里发现知识库的缺口。
CREATE TABLE IF NOT EXISTS qa_log (
    id             UUID PRIMARY KEY,
    question       TEXT        NOT NULL,
    retrieved      INT         NOT NULL DEFAULT 0,
    answered       BOOLEAN     NOT NULL DEFAULT TRUE,
    first_token_ms INT,
    total_ms       INT,
    client_ip      TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_qa_log_created_at ON qa_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_lab_member_sort ON lab_member (sort_order);
