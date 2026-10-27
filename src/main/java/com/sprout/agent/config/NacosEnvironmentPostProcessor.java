package com.sprout.agent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ByteArrayResource;

public class NacosEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        loadLocalEnvFile(environment);
        String address = environment.getProperty("NACOS_SERVER_ADDR", "10.244.0.111:8848");
        String username = required(environment, "NACOS_USERNAME");
        String password = required(environment, "NACOS_PASSWORD");
        String dataId = environment.getProperty("NACOS_DATA_ID", "sprout-agent-zerotier.yaml");
        String group = environment.getProperty("NACOS_GROUP", "DEFAULT_GROUP");
        String namespace = environment.getProperty(
                "NACOS_NAMESPACE", environment.getProperty("sprout.nacos.namespace", "sprout_agent"));

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
            URI baseUri = nacosBaseUri(address);
            String token = login(client, baseUri, username, password);
            String content = fetchConfig(client, baseUri, token, dataId, group, namespace);
            List<PropertySource<?>> propertySources = new YamlPropertySourceLoader().load(
                    "nacos-" + dataId, new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)));
            for (int i = propertySources.size() - 1; i >= 0; i--) {
                environment.getPropertySources().addFirst(propertySources.get(i));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load Nacos configuration " + dataId, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading Nacos configuration " + dataId, exception);
        }
    }

    private static void loadLocalEnvFile(ConfigurableEnvironment environment) {
        Path path = Path.of(environment.getProperty("SPROUT_ENV_FILE", ".env"));
        if (!Files.isRegularFile(path)) return;

        Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int separator = trimmed.indexOf('=');
                if (separator <= 0) continue;
                String key = trimmed.substring(0, separator).trim();
                String value = trimmed.substring(separator + 1).trim();
                if (key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    if (value.length() >= 2
                            && ((value.startsWith("\"") && value.endsWith("\""))
                                    || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    values.put(key, value);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read local environment file " + path, exception);
        }
        if (!values.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource("localDotEnv", values));
        }
    }

    private static String required(ConfigurableEnvironment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + key);
        }
        return value;
    }

    private static URI nacosBaseUri(String address) {
        URI uri = URI.create(address.contains("://") ? address : "http://" + address);
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/$", "");
        if (!path.endsWith("/nacos")) path += "/nacos";
        return URI.create(uri.getScheme() + "://" + uri.getAuthority() + path);
    }

    private static String login(HttpClient client, URI baseUri, String username, String password)
            throws IOException, InterruptedException {
        String body = form("username", username) + "&" + form("password", password);
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/nacos/v1/auth/login"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Nacos authentication failed with HTTP " + response.statusCode());
        }
        JsonNode token = JSON.readTree(response.body()).get("accessToken");
        if (token == null || token.asText().isBlank()) {
            throw new IllegalStateException("Nacos authentication response did not include an access token");
        }
        return token.asText();
    }

    private static String fetchConfig(
            HttpClient client, URI baseUri, String token, String dataId, String group, String namespace)
            throws IOException, InterruptedException {
        String query = "dataId=" + encode(dataId)
                + "&group=" + encode(group)
                + "&tenant=" + encode(namespace)
                + "&accessToken=" + encode(token);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUri + "/v1/cs/configs?" + query))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().isBlank()
                || response.body().startsWith("config data not exist")) {
            throw new IllegalStateException("Nacos configuration " + dataId + " is missing or unreadable (HTTP "
                    + response.statusCode() + ")");
        }
        return response.body();
    }

    private static String form(String key, String value) {
        return encode(key) + "=" + encode(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
