package com.sprout.agent.storage;

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

    @ConfigurationProperties("sprout.pgvector")
    public record PgVectorProperties(
            String url, String username, String password, int dimensions, boolean initializeSchema) {
    }
}
