package com.programmersdiary.aidaemon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
            log.error("Failed to connect MCP server {}: {}", name, e.getMessage(), e);
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
                .filter(normalizeNonJson2xxToJsonWithRequestId());
        if (config.headers() != null && !config.headers().isEmpty()) {
            config.headers().forEach(webClientBuilder::defaultHeader);
        }
        return WebClientStreamableHttpTransport.builder(webClientBuilder)
                .endpoint(endpoint)
                .build();
    }

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static ExchangeFilterFunction normalizeNonJson2xxToJsonWithRequestId() {
        return (request, next) -> {
            AtomicReference<byte[]> requestBodyRef = new AtomicReference<>();
            CountDownLatch bodySentLatch = new CountDownLatch(1);
            ClientRequest capturingRequest = wrapRequestWithBodyCapture(request, requestBodyRef, bodySentLatch);
            return next.exchange(capturingRequest)
                    .single()
                    .flatMap(response -> {
                        if (request.method() != HttpMethod.POST) {
                            return Mono.just(response);
                        }
                        if (!response.statusCode().is2xxSuccessful()) {
                            return Mono.just(response);
                        }
                        var status = response.statusCode();
                        var contentType = response.headers().contentType().orElse(MediaType.APPLICATION_JSON);
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(responseBody -> Mono.fromCallable(() -> {
                                    try {
                                        bodySentLatch.await(500, TimeUnit.MILLISECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    byte[] captured = requestBodyRef.get();
                                    if (captured != null) {
                                        String rewritten = rewriteResponseIdFromRequest(responseBody, captured);
                                        if (rewritten != null) {
                                            return ClientResponse.create(HttpStatus.OK)
                                                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                                    .body(rewritten)
                                                    .build();
                                        }
                                        if (status.value() == 202) {
                                            String synthetic = buildJsonRpcResponseWithId(captured);
                                            if (synthetic != null) {
                                                return ClientResponse.create(HttpStatus.OK)
                                                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                                        .body(synthetic)
                                                        .build();
                                            }
                                        }
                                    }
                                    return ClientResponse.create(status)
                                            .header("Content-Type", contentType.toString())
                                            .body(responseBody)
                                            .build();
                                }))
                                .doOnError(e -> log.warn("MCP Smithery filter error", e));
                    })
                    .doOnError(e -> log.warn("MCP Smithery exchange error", e));
        };
    }

    private static ClientRequest wrapRequestWithBodyCapture(ClientRequest request, AtomicReference<byte[]> bodyRef, CountDownLatch bodySentLatch) {
        BodyInserter<?, ? super ClientHttpRequest> original = request.body();
        BodyInserter<?, ? super ClientHttpRequest> capturing = (output, context) -> {
            ClientHttpRequest proxy = (ClientHttpRequest) Proxy.newProxyInstance(
                    ClientHttpRequest.class.getClassLoader(),
                    new Class<?>[]{ClientHttpRequest.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if ("writeWith".equals(method.getName()) && args != null && args.length == 1) {
                                @SuppressWarnings("unchecked")
                                org.reactivestreams.Publisher<? extends DataBuffer> body =
                                        (org.reactivestreams.Publisher<? extends DataBuffer>) args[0];
                                List<byte[]> chunks = new ArrayList<>();
                                DataBufferFactory factory = output.bufferFactory();
                                Flux<DataBuffer> copied = Flux.from(body).map(db -> {
                                    byte[] arr = new byte[db.readableByteCount()];
                                    db.read(arr);
                                    DataBufferUtils.release(db);
                                    chunks.add(arr);
                                    DataBuffer buf = factory.allocateBuffer(arr.length);
                                    buf.write(arr);
                                    return buf;
                                }).doOnComplete(() -> {
                                    int total = chunks.stream().mapToInt(a -> a.length).sum();
                                    byte[] full = new byte[total];
                                    int offset = 0;
                                    for (byte[] chunk : chunks) {
                                        System.arraycopy(chunk, 0, full, offset, chunk.length);
                                        offset += chunk.length;
                                    }
                                    bodyRef.set(full);
                                    log.debug("MCP Smithery: request body captured, length={}", full.length);
                                    bodySentLatch.countDown();
                                }).doOnError(e -> {
                                    log.warn("MCP Smithery: body capture error", e);
                                    bodySentLatch.countDown();
                                })
                                .doOnCancel(bodySentLatch::countDown);
                                return method.invoke(output, copied);
                            }
                            return method.invoke(output, args);
                        }
                    });
            return original.insert(proxy, context);
        };
        return ClientRequest.from(request).body(capturing).build();
    }

    private static String rewriteResponseIdFromRequest(String responseBody, byte[] requestBody) {
        if (responseBody == null || requestBody == null || requestBody.length == 0) return null;
        try {
            JsonNode reqRoot = JSON_MAPPER.readTree(requestBody);
            if (reqRoot == null || !reqRoot.has("id") || reqRoot.get("id").isNull()) return null;
            JsonNode respRoot = JSON_MAPPER.readTree(responseBody);
            if (respRoot == null || !respRoot.isObject()) return null;
            ObjectNode resp = (ObjectNode) respRoot;
            resp.set("id", reqRoot.get("id"));
            return JSON_MAPPER.writeValueAsString(resp);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractIdFromBody(byte[] requestBody) {
        if (requestBody == null || requestBody.length == 0) return null;
        try {
            JsonNode root = JSON_MAPPER.readTree(requestBody);
            return root != null && root.has("id") ? root.get("id").toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractMethodFromBody(byte[] requestBody) {
        if (requestBody == null || requestBody.length == 0) return null;
        try {
            JsonNode root = JSON_MAPPER.readTree(requestBody);
            JsonNode m = root != null && root.has("method") ? root.get("method") : null;
            return m != null ? m.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildJsonRpcResponseWithId(byte[] requestBody) {
        String idJson = null;
        String method = null;
        if (requestBody != null && requestBody.length > 0) {
            try {
                JsonNode root = JSON_MAPPER.readTree(requestBody);
                if (root != null) {
                    if (root.has("id") && !root.get("id").isNull()) {
                        idJson = JSON_MAPPER.writeValueAsString(root.get("id"));
                    }
                    if (root.has("method")) {
                        JsonNode m = root.get("method");
                        method = m != null ? m.asText() : null;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        ObjectNode result = JSON_MAPPER.createObjectNode();
        if ("initialize".equals(method)) {
            result.put("protocolVersion", "2024-11-05");
            ObjectNode caps = result.putObject("capabilities");
            caps.putObject("tools").put("listChanged", true);
            result.putObject("serverInfo").put("name", "smithery-placeholder").put("version", "0.0.0");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",\"id\":");
        sb.append(idJson != null ? idJson : "null");
        sb.append(",\"result\":");
        sb.append(JSON_MAPPER.valueToTree(result).toString());
        sb.append("}");
        return sb.toString();
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
