-- 第 5 课：解题助手（提示词、技能、会话、消息）
-- MySQL 8 原生 JSON；所有班级相关表带 class_id 并加外键

CREATE TABLE system_prompts (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    class_id    BIGINT NOT NULL,
    content     TEXT NOT NULL,
    updated_by  BIGINT NOT NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_system_prompts_class (class_id),
    CONSTRAINT fk_prompts_class FOREIGN KEY (class_id) REFERENCES class_rooms(id),
    CONSTRAINT fk_prompts_user FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_skills (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    class_id    BIGINT NOT NULL,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    content     TEXT NOT NULL,
    enabled     TINYINT(1) NOT NULL DEFAULT 1,
    updated_by  BIGINT,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_skills_class_code (class_id, code),
    CONSTRAINT fk_skills_class FOREIGN KEY (class_id) REFERENCES class_rooms(id),
    CONSTRAINT fk_skills_user FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chat_sessions (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    class_id    BIGINT NOT NULL,
    owner_id    BIGINT NOT NULL,
    title       VARCHAR(200) NOT NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_sessions_owner (owner_id, class_id),
    CONSTRAINT fk_sessions_class FOREIGN KEY (class_id) REFERENCES class_rooms(id),
    CONSTRAINT fk_sessions_owner FOREIGN KEY (owner_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chat_messages (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    session_id  BIGINT NOT NULL,
    class_id    BIGINT NOT NULL,
    role        VARCHAR(16) NOT NULL,
    content     MEDIUMTEXT NOT NULL,
    basis       VARCHAR(16),
    citations   JSON,
    skill_code  VARCHAR(64),
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_messages_session (session_id, id),
    CONSTRAINT fk_messages_session FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_class FOREIGN KEY (class_id) REFERENCES class_rooms(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
