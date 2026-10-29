CREATE TABLE IF NOT EXISTS conversations (
    id VARCHAR(32) NOT NULL,
    title VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    INDEX idx_conversations_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(32) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    file_id VARCHAR(32) NULL,
    file_name VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_chat_messages_conversation_id (conversation_id, id),
    CONSTRAINT fk_chat_messages_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_turn_details (
    assistant_message_id BIGINT NOT NULL,
    thinking MEDIUMTEXT NULL,
    tools_json JSON NOT NULL,
    references_json JSON NOT NULL,
    recommendations_json JSON NULL,
    first_response_ms BIGINT NULL,
    total_response_ms BIGINT NOT NULL,
    PRIMARY KEY (assistant_message_id),
    CONSTRAINT fk_chat_turn_details_message
        FOREIGN KEY (assistant_message_id) REFERENCES chat_messages (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS uploaded_files (
    id VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    extracted_text MEDIUMTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
