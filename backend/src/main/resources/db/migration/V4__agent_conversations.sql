-- 第 5 课（新）：可配置智能体、绑定关系、文件式技能与会话锁定智能体
-- 迁移策略：只加表/加列/回填，不删旧列；升级后老会话立即可用。

CREATE TABLE assistants (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    class_id    BIGINT NOT NULL,
    name        VARCHAR(64) NOT NULL,
    role_prompt TEXT,
    enabled     TINYINT(1) NOT NULL DEFAULT 1,
    updated_by  BIGINT,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_assistants_class_name (class_id, name),
    CONSTRAINT fk_assistants_class FOREIGN KEY (class_id) REFERENCES class_rooms(id),
    CONSTRAINT fk_assistants_user FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE assistant_skills (
    assistant_id BIGINT NOT NULL,
    skill_id     BIGINT NOT NULL,
    PRIMARY KEY (assistant_id, skill_id),
    CONSTRAINT fk_as_agent FOREIGN KEY (assistant_id) REFERENCES assistants(id) ON DELETE CASCADE,
    CONSTRAINT fk_as_skill FOREIGN KEY (skill_id) REFERENCES ai_skills(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE assistant_materials (
    assistant_id BIGINT NOT NULL,
    material_id  BIGINT NOT NULL,
    PRIMARY KEY (assistant_id, material_id),
    CONSTRAINT fk_am_agent FOREIGN KEY (assistant_id) REFERENCES assistants(id) ON DELETE CASCADE,
    CONSTRAINT fk_am_material FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ai_skills 扩展为文件解析模型（名称上限收到 32 字，与课件一致）
ALTER TABLE ai_skills
    ADD COLUMN conditions VARCHAR(120) NOT NULL DEFAULT '',
    ADD COLUMN subject_scope VARCHAR(32) NOT NULL DEFAULT '',
    ADD COLUMN tool VARCHAR(32) NOT NULL DEFAULT 'knowledge_search',
    ADD COLUMN raw_text TEXT,
    MODIFY name VARCHAR(32) NOT NULL,
    MODIFY description VARCHAR(500) NOT NULL DEFAULT '';

-- 会话锁定智能体；消息记录命中技能名
ALTER TABLE chat_sessions
    ADD COLUMN agent_id BIGINT;
ALTER TABLE chat_messages
    ADD COLUMN skill_name VARCHAR(32);

-- ===== 存量数据迁移 =====
-- 1) 每班一个默认智能体，角色提示词取该班已保存提示词（无则 NULL → 运行时默认角色）
INSERT INTO assistants (class_id, name, role_prompt, enabled, updated_by)
SELECT c.id, '解题助手', p.content, 1, p.updated_by
FROM class_rooms c
LEFT JOIN system_prompts p ON p.class_id = c.id
WHERE NOT EXISTS (SELECT 1 FROM assistants a WHERE a.class_id = c.id AND a.name = '解题助手');

-- 2) 老会话回填默认智能体
UPDATE chat_sessions cs
JOIN assistants a ON a.class_id = cs.class_id AND a.name = '解题助手'
SET cs.agent_id = a.id
WHERE cs.agent_id IS NULL;

-- 3) 每班把「已启用技能 + 全班材料」绑定到默认智能体（升级后老会话下一轮立即可用）
INSERT INTO assistant_skills (assistant_id, skill_id)
SELECT a.id, s.id
FROM assistants a
JOIN ai_skills s ON s.class_id = a.class_id AND s.enabled = 1
WHERE a.name = '解题助手'
  AND NOT EXISTS (SELECT 1 FROM assistant_skills x WHERE x.assistant_id = a.id AND x.skill_id = s.id);

INSERT INTO assistant_materials (assistant_id, material_id)
SELECT a.id, m.id
FROM assistants a
JOIN materials m ON m.class_id = a.class_id
WHERE a.name = '解题助手'
  AND NOT EXISTS (SELECT 1 FROM assistant_materials x WHERE x.assistant_id = a.id AND x.material_id = m.id);

-- 4) 存量技能回填 raw_text（文件格式：元数据区 + 空行 + 正文）
UPDATE ai_skills
SET raw_text = CONCAT(
        '名称：', name, '\n',
        '适用条件：', conditions, '\n',
        '资料：', subject_scope, '\n',
        '工具：', tool, '\n',
        '启用：', IF(enabled = 1, '是', '否'), '\n\n',
        content
    )
WHERE raw_text IS NULL;

-- 5) 收尾约束：agent_id 必填 + 外键
ALTER TABLE chat_sessions
    MODIFY agent_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_sessions_agent FOREIGN KEY (agent_id) REFERENCES assistants(id);
