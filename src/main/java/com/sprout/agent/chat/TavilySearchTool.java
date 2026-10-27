package com.sprout.agent.chat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TavilySearchTool {

    private final RestClient client;

    public TavilySearchTool(@Value("${sprout.tavily.api-key}") String apiKey) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        this.client = RestClient.builder()
                .baseUrl("https://api.tavily.com")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(requestFactory)
                .build();
    }

    @Tool(description = "Search the current web for recent facts, news, or information not in the conversation. Return source links with summaries.")
    public String searchWeb(String query) {
        SearchResponse response = client.post().uri("/search")
                .body(Map.of("query", query, "search_depth", "basic", "max_results", 5))
                .retrieve().body(SearchResponse.class);
        if (response == null || response.results() == null || response.results().isEmpty()) {
            return "No web search results found.";
        }
        StringBuilder result = new StringBuilder();
        for (SearchResult item : response.results()) {
            result.append(item.title()).append("\n")
                    .append(item.url()).append("\n")
                    .append(item.content()).append("\n\n");
        }
        return result.toString();
    }

    private record SearchResponse(List<SearchResult> results) {
    }

    private record SearchResult(String title, String url, String content) {
    }
}
