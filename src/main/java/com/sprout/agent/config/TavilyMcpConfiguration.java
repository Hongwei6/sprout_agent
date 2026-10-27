package com.sprout.agent.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.net.http.HttpRequest;
import java.time.Duration;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TavilyMcpConfiguration {

    @Bean(destroyMethod = "close")
    McpSyncClient tavilyMcpClient(@Value("${sprout.tavily.api-key}") String apiKey) {
        var transport = HttpClientStreamableHttpTransport.builder("https://mcp.tavily.com/mcp/")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + apiKey))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(120)).build();
        try {
            client.initialize();
            return client;
        } catch (RuntimeException ex) {
            client.close();
            throw ex;
        }
    }

    @Bean
    SyncMcpToolCallbackProvider tavilyToolCallbacks(McpSyncClient tavilyMcpClient) {
        return SyncMcpToolCallbackProvider.builder().mcpClients(tavilyMcpClient).build();
    }
}
