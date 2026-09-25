-- =============================================================================

-- ⑤ Encrypted model credentials managed from the local settings page
CREATE TABLE IF NOT EXISTS model_credential (
    provider VARCHAR(32) NOT NULL PRIMARY KEY,
    encrypted_api_key TEXT NOT NULL,
    updated_at DATETIME(6) NOT NULL
);
-- LEGACY REFERENCE: AgentFS 文件搜索全文索引（MySQL 8）
-- 正式环境请使用 Flyway 迁移目录：
--   backend/src/main/resources/db/migration/mysql/
-- 本文件不再作为生产环境初始化入口，避免绕过 flyway_schema_history。
--
-- 说明：
--   1. FileMetadata.file_name / summary 已改为 TEXT 类型（columnDefinition = "TEXT"）。
--      若数据库中这两列仍是 VARCHAR（旧表结构），先执行步骤 ① 迁移列类型。
--   2. 执行本脚本后，将 application.yml 中 prod 环境的 jpa.hibernate.ddl-auto 置为
--      validate（应用启动仅校验表结构，不再自动变更；FULLTEXT 索引由本脚本维护）。
--   3. 依赖 MySQL 内置 ngram 分词器，无需额外安装插件，开箱即支持中文分词。
-- =============================================================================

-- ① 调整列类型为 TEXT（新库自动建表可跳过；旧库 VARCHAR 必须迁移）
ALTER TABLE file_metadata MODIFY COLUMN file_name TEXT NOT NULL;
ALTER TABLE file_metadata MODIFY COLUMN summary TEXT;

-- ② 创建中文全文索引（ngram 分词，file_name + summary 组合检索，命中按相关性降序）
CREATE FULLTEXT INDEX idx_file_search ON file_metadata(file_name, summary) WITH PARSER ngram;

-- ③ 分类目录归档新增列（分类目录归档任务清单 T2.2）
--    说明：dev（H2，ddl-auto=update）重启自动加列，无需手动；
--         prod（MySQL，ddl-auto=validate）必须手动补列，否则启动校验失败。
--         注意：Java boolean 在 Hibernate 6 + MySQL 8 下默认映射为 bit(1)；
--         若 validate 报列类型不匹配，以应用启动时 Hibernate 期望的列类型为准调整
--         （BOOLEAN / bit(1) 语义等价，二者均可）。
ALTER TABLE file_metadata ADD COLUMN category VARCHAR(30) NOT NULL DEFAULT 'OTHER';
ALTER TABLE file_metadata ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

-- ④ 校验（可选）
-- SHOW INDEX FROM file_metadata WHERE Key_name = 'idx_file_search';
-- EXPLAIN SELECT * FROM file_metadata WHERE MATCH(file_name, summary) AGAINST('合同' IN NATURAL LANGUAGE MODE);

-- ⑤ Encrypted model credentials managed from the local settings page
CREATE TABLE IF NOT EXISTS model_credential (
    provider VARCHAR(32) NOT NULL PRIMARY KEY,
    encrypted_api_key TEXT NOT NULL,
    updated_at DATETIME(6) NOT NULL
);
