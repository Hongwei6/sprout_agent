package com.sprout.agent.storage;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MinioConfiguration.MinioProperties.class)
public class MinioConfiguration {

    @Bean
    MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @ConfigurationProperties("sprout.minio")
    public record MinioProperties(String endpoint, String accessKey, String secretKey, String bucket) {
    }
}
