CREATE TABLE knowledge_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    material_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    body_text LONGTEXT NOT NULL,
    char_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_entries_material (material_id),
    KEY idx_knowledge_entries_class (class_id),
    CONSTRAINT fk_entries_material FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE CASCADE,
    CONSTRAINT fk_entries_class FOREIGN KEY (class_id) REFERENCES class_rooms(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_chunks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_entry_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    chunk_text MEDIUMTEXT NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    index_status VARCHAR(16) NOT NULL,
    failure_reason VARCHAR(500),
    strategy VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_chunks_class_status (class_id, index_status),
    KEY idx_chunks_material (material_id, chunk_index),
    FULLTEXT KEY ft_chunks_text (chunk_text) WITH PARSER ngram,
    CONSTRAINT fk_chunks_entry FOREIGN KEY (knowledge_entry_id) REFERENCES knowledge_entries(id) ON DELETE CASCADE,
    CONSTRAINT fk_chunks_material FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE CASCADE,
    CONSTRAINT fk_chunks_class FOREIGN KEY (class_id) REFERENCES class_rooms(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
