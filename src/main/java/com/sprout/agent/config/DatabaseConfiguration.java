package com.sprout.agent.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(DatabaseConfiguration.PgVectorProperties.class)
public class DatabaseConfiguration {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties mysqlDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    DataSource mysqlDataSource(DataSourceProperties mysqlDataSourceProperties) {
        return mysqlDataSourceProperties.initializeDataSourceBuilder().build();
    }

    @Bean("pgVectorDataSource")
    DataSource pgVectorDataSource(PgVectorProperties properties) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(properties.url())
                .username(properties.username())
                .password(properties.password())
                .build();
    }

    @Bean("pgVectorJdbcTemplate")
    JdbcTemplate pgVectorJdbcTemplate(@Qualifier("pgVectorDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    VectorStore vectorStore(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel,
            PgVectorProperties properties) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(properties.dimensions())
                .initializeSchema(properties.initializeSchema())
                .build();
    }
    @Bean("fileVectorStore")
    VectorStore fileVectorStore(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel,
            PgVectorProperties properties) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("vector_file_info")
                .dimensions(properties.dimensions())
                .initializeSchema(true)
                .build();
    }

    @Bean
    ApplicationRunner migrateConversationColumns(@Qualifier("mysqlDataSource") DataSource dataSource) {
        return args -> {
            try (var connection = dataSource.getConnection()) {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS uploaded_files (
                                id VARCHAR(32) NOT NULL PRIMARY KEY,
                                name VARCHAR(255) NOT NULL,
                                content_type VARCHAR(100) NOT NULL,
                                size_bytes BIGINT NOT NULL,
                                extracted_text MEDIUMTEXT NULL,
                                indexed BOOLEAN NOT NULL DEFAULT FALSE,
                                created_at DATETIME(6) NOT NULL
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                            """);
                }
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS ai_ppt_inst (
                                id BIGINT NOT NULL AUTO_INCREMENT,
                                conversation_id VARCHAR(32) NOT NULL,
                                query VARCHAR(4000) NOT NULL,
                                status VARCHAR(32) NOT NULL,
                                failed_stage VARCHAR(32) NULL,
                                requirement MEDIUMTEXT NULL,
                                search_info MEDIUMTEXT NULL,
                                template_code MEDIUMTEXT NULL,
                                outline MEDIUMTEXT NULL,
                                ppt_schema MEDIUMTEXT NULL,
                                error MEDIUMTEXT NULL,
                                created_at DATETIME(6) NOT NULL,
                                updated_at DATETIME(6) NOT NULL,
                                PRIMARY KEY (id),
                                INDEX idx_ai_ppt_inst_conversation_id (conversation_id, id),
                                CONSTRAINT fk_ai_ppt_inst_conversation
                                    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                            """);
                }
                try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null,
                        "ai_ppt_inst", "ppt_schema")) {
                    if (!columns.next()) {
                        try (var statement = connection.createStatement()) {
                            statement.executeUpdate("ALTER TABLE ai_ppt_inst ADD COLUMN ppt_schema MEDIUMTEXT NULL");
                        }
                    }
                }
                try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null,
                        "uploaded_files", "indexed")) {
                    if (!columns.next()) {
                        try (var statement = connection.createStatement()) {
                            statement.executeUpdate("ALTER TABLE uploaded_files ADD COLUMN indexed BOOLEAN NOT NULL DEFAULT FALSE");
                        }
                    }
                }
                for (String column : new String[]{"file_id", "file_name"}) {
                    try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null,
                            "chat_messages", column)) {
                        if (!columns.next()) {
                            try (var statement = connection.createStatement()) {
                                statement.executeUpdate("ALTER TABLE chat_messages ADD COLUMN "
                                        + column + " VARCHAR(" + ("file_id".equals(column) ? "32" : "255") + ") NULL");
                            }
                        }
                    }
                }
                try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null,
                        "chat_turn_details", "recommendations_json")) {
                    if (!columns.next()) {
                        try (var statement = connection.createStatement()) {
                            statement.executeUpdate("ALTER TABLE chat_turn_details ADD COLUMN recommendations_json JSON NULL");
                        }
                    }
                }
                try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null,
                        "conversations", "archived")) {
                    if (!columns.next()) {
                        try (var statement = connection.createStatement()) {
                            statement.executeUpdate("ALTER TABLE conversations ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE");
                        }
                    }
                }
            }
        };
    }


    @ConfigurationProperties("sprout.pgvector")
    public record PgVectorProperties(
            String url, String username, String password, int dimensions, boolean initializeSchema) {
    }
}
