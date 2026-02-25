package com.programmersdiary.aidaemon.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class McpService {

    private static final Logger log = LoggerFactory.getLogger(McpService.class);

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path mcpsDir;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    public McpService(
            @Value("${aidaemon.config-dir:${user.home}/.aidaemon}") String configDir) {
        this.mcpsDir = Path.of(configDir, "mcps");
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(mcpsDir);
        loadAll();
    }

    @PreDestroy
    void shutdown() {
        clients.values().forEach(client -> {
            try {
                client.closeGracefully();
            } catch (Exception e) {
                log.warn("Error closing MCP client: {}", e.getMessage());
            }
        });
        clients.clear();
    }

    public void loadAll() {
        shutdown();
        try (var stream = Files.list(mcpsDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(this::loadConfig);
        } catch (IOException e) {
            log.error("Failed to list MCP configs", e);
        }
    }

    private void loadConfig(Path configFile) {
        try {
            var config = objectMapper.readValue(configFile.toFile(), McpServerConfig.class);
            var name = config.name() != null ? config.name()
                    : configFile.getFileName().toString().replace(".json", "");
            connectClient(name, config);
        } catch (Exception e) {
            log.error("Failed to load MCP config from {}: {}", configFile.getFileName(), e.getMessage());
        }
    }

    private void connectClient(String name, McpServerConfig config) {
        try {
            var transport = createTransport(config);
            var client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();
            client.initialize();
            clients.put(name, client);
            log.info("Connected MCP server: {} ({})", name, config.type());
        } catch (Exception e) {
            log.error("Failed to connect MCP server {}: {}", name, e.getMessage());
        }
    }

    private McpClientTransport createTransport(McpServerConfig config) {
        var type = config.type() != null ? config.type() : McpTransportType.LOCAL;
        return switch (type) {
            case REMOTE -> createStreamableHttpTransport(config);
            case SSE -> createSseTransport(config);
            case LOCAL -> createStdioTransport(config);
        };
    }

    private McpClientTransport createStreamableHttpTransport(McpServerConfig config) {
        if (config.url() != null && config.url().contains("smithery.ai")) {
            return createSmitheryStreamableHttpTransport(config);
        }
        var builder = HttpClientStreamableHttpTransport.builder(config.url())
                .openConnectionOnStartup(false);
        if (config.headers() != null && !config.headers().isEmpty()) {
            builder.customizeRequest(req -> config.headers().forEach(req::header));
        }
        return builder.build();
    }

    private McpClientTransport createSmitheryStreamableHttpTransport(McpServerConfig config) {
        URI uri = URI.create(config.url());
        String baseUrl = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String endpoint = (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) ? uri.getRawPath() : "/mcp";
        WebClient.Builder webClientBuilder = WebClient.builder()
                .baseUrl(baseUrl)
                .filter(acceptTextHtmlAsEmptyJson());
        if (config.headers() != null && !config.headers().isEmpty()) {
            config.headers().forEach(webClientBuilder::defaultHeader);
        }
        return WebClientStreamableHttpTransport.builder(webClientBuilder)
                .endpoint(endpoint)
                .build();
    }

    private static ExchangeFilterFunction acceptTextHtmlAsEmptyJson() {
        return (request, next) -> next.exchange(request)
                .flatMap(response -> {
                    if (response.statusCode().is2xxSuccessful()
                            && response.headers().contentType().isPresent()
                            && response.headers().contentType().get().includes(MediaType.TEXT_HTML)) {
                        return response.bodyToMono(Void.class)
                                .then(Mono.fromCallable(() -> ClientResponse.create(HttpStatus.ACCEPTED)
                                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                        .build()));
                    }
                    return Mono.just(response);
                });
    }

    private McpClientTransport createSseTransport(McpServerConfig config) {
        var builder = HttpClientSseClientTransport.builder(config.url());
        if (config.headers() != null && !config.headers().isEmpty()) {
            builder.customizeRequest(req -> config.headers().forEach(req::header));
        }
        return builder.build();
    }

    private McpClientTransport createStdioTransport(McpServerConfig config) {
        var args = config.args() != null ? config.args() : List.<String>of();
        var env = config.env() != null ? config.env() : Map.<String, String>of();

        var isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        ServerParameters serverParams;
        if (isWindows && !config.command().equalsIgnoreCase("cmd.exe")) {
            var winArgs = new java.util.ArrayList<String>();
            winArgs.add("/c");
            winArgs.add(config.command());
            winArgs.addAll(args);
            serverParams = ServerParameters.builder("cmd.exe")
                    .args(winArgs.toArray(new String[0]))
                    .env(env)
                    .build();
        } else {
            serverParams = ServerParameters.builder(config.command())
                    .args(args.toArray(new String[0]))
                    .env(env)
                    .build();
        }
        return new StdioClientTransport(serverParams, McpJsonMapper.createDefault());
    }

    public List<ToolCallback> getToolCallbacks() {
        if (clients.isEmpty()) {
            return List.of();
        }
        var provider = new SyncMcpToolCallbackProvider(List.copyOf(clients.values()));
        return List.of(provider.getToolCallbacks());
    }

    public Map<String, List<ToolCallback>> getToolCallbacksByServer() {
        var result = new java.util.LinkedHashMap<String, List<ToolCallback>>();
        clients.forEach((serverName, client) -> {
            var provider = new SyncMcpToolCallbackProvider(List.of(client));
            result.put(serverName, List.of(provider.getToolCallbacks()));
        });
        return result;
    }

    public List<String> getConnectedServers() {
        return List.copyOf(clients.keySet());
    }

    public void addServer(String name, McpServerConfig config) {
        var configFile = mcpsDir.resolve(name + ".json");
        try {
            objectMapper.writeValue(configFile.toFile(), config);
            connectClient(name, config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeServerConfig(String name, McpServerConfig config) {
        var configFile = mcpsDir.resolve(name + ".json");
        try {
            objectMapper.writeValue(configFile.toFile(), config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean removeServer(String name) {
        var client = clients.remove(name);
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (Exception e) {
                log.warn("Error closing MCP client {}: {}", name, e.getMessage());
            }
        }
        var configFile = mcpsDir.resolve(name + ".json");
        try {
            return Files.deleteIfExists(configFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
