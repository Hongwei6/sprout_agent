package com.sprout.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(excludeName = "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration")
public class SproutAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SproutAgentApplication.class, args);
    }
}
