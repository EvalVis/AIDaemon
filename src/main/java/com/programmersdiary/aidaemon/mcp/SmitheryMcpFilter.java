package com.programmersdiary.aidaemon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class SmitheryMcpFilter {

    private static final Logger log = LoggerFactory.getLogger(SmitheryMcpFilter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static ExchangeFilterFunction filter() {
        return (request, next) -> {
            if (request.method() != HttpMethod.POST) {
                return next.exchange(request);
            }
            AtomicReference<byte[]> requestBodyRef = new AtomicReference<>();
            CountDownLatch bodySent = new CountDownLatch(1);
            ClientRequest capturing = wrapBodyCapture(request, requestBodyRef, bodySent);
            return next.exchange(capturing)
                    .single()
                    .flatMap(response -> normalizePostResponse(response, requestBodyRef, bodySent))
                    .doOnError(e -> log.warn("MCP Smithery exchange error", e));
        };
    }

    private static Mono<ClientResponse> normalizePostResponse(
            ClientResponse response,
            AtomicReference<byte[]> requestBodyRef,
            CountDownLatch bodySent) {
        if (!response.statusCode().is2xxSuccessful()) {
            return Mono.just(response);
        }
        var status = response.statusCode();
        var contentType = response.headers().contentType().orElse(MediaType.APPLICATION_JSON);
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(responseBody -> Mono.fromCallable(() -> {
                    awaitBody(bodySent);
                    byte[] captured = requestBodyRef.get();
                    if (captured != null) {
                        String rewritten = rewriteId(responseBody, captured);
                        if (rewritten != null) {
                            return okJson(rewritten);
                        }
                        if (status.value() == 202) {
                            String synthetic = syntheticJsonRpcResponse(captured);
                            if (synthetic != null) {
                                return okJson(synthetic);
                            }
                        }
                    }
                    return ClientResponse.create(status)
                            .header("Content-Type", contentType.toString())
                            .body(responseBody)
                            .build();
                }))
                .doOnError(e -> log.warn("MCP Smithery filter error", e));
    }

    private static ClientResponse okJson(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static void awaitBody(CountDownLatch latch) {
        try {
            latch.await(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ClientRequest wrapBodyCapture(
            ClientRequest request,
            AtomicReference<byte[]> bodyRef,
            CountDownLatch bodySent) {
        BodyInserter<?, ? super ClientHttpRequest> original = request.body();
        BodyInserter<?, ? super ClientHttpRequest> capturing = (output, context) -> {
            ClientHttpRequest proxy = (ClientHttpRequest) Proxy.newProxyInstance(
                    ClientHttpRequest.class.getClassLoader(),
                    new Class<?>[]{ClientHttpRequest.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if (!"writeWith".equals(method.getName()) || args == null || args.length != 1) {
                                return method.invoke(output, args);
                            }
                            @SuppressWarnings("unchecked")
                            var body = (org.reactivestreams.Publisher<? extends DataBuffer>) args[0];
                            List<byte[]> chunks = new ArrayList<>();
                            DataBufferFactory factory = output.bufferFactory();
                            Flux<DataBuffer> copied = Flux.from(body)
                                    .map(db -> {
                                        byte[] arr = new byte[db.readableByteCount()];
                                        db.read(arr);
                                        DataBufferUtils.release(db);
                                        chunks.add(arr);
                                        DataBuffer buf = factory.allocateBuffer(arr.length);
                                        buf.write(arr);
                                        return buf;
                                    })
                                    .doOnComplete(() -> {
                                        int total = chunks.stream().mapToInt(a -> a.length).sum();
                                        byte[] full = new byte[total];
                                        int offset = 0;
                                        for (byte[] chunk : chunks) {
                                            System.arraycopy(chunk, 0, full, offset, chunk.length);
                                            offset += chunk.length;
                                        }
                                        bodyRef.set(full);
                                        log.debug("MCP Smithery: request body captured, length={}", full.length);
                                        bodySent.countDown();
                                    })
                                    .doOnError(e -> {
                                        log.warn("MCP Smithery: body capture error", e);
                                        bodySent.countDown();
                                    })
                                    .doOnCancel(bodySent::countDown);
                            return method.invoke(output, copied);
                        }
                    });
            return original.insert(proxy, context);
        };
        return ClientRequest.from(request).body(capturing).build();
    }

    private static String rewriteId(String responseBody, byte[] requestBody) {
        if (responseBody == null || requestBody == null || requestBody.length == 0) return null;
        try {
            JsonNode req = JSON.readTree(requestBody);
            if (req == null || !req.has("id") || req.get("id").isNull()) return null;
            JsonNode resp = JSON.readTree(responseBody);
            if (resp == null || !resp.isObject()) return null;
            ((ObjectNode) resp).set("id", req.get("id"));
            return JSON.writeValueAsString(resp);
        } catch (Exception e) {
            return null;
        }
    }

    private static String syntheticJsonRpcResponse(byte[] requestBody) {
        if (requestBody == null || requestBody.length == 0) return null;
        try {
            JsonNode root = JSON.readTree(requestBody);
            if (root == null) return null;
            String idJson = root.has("id") && !root.get("id").isNull()
                    ? JSON.writeValueAsString(root.get("id")) : "null";
            String method = root.has("method") ? root.get("method").asText() : null;
            ObjectNode result = JSON.createObjectNode();
            if ("initialize".equals(method)) {
                result.put("protocolVersion", "2024-11-05");
                result.putObject("capabilities").putObject("tools").put("listChanged", true);
                result.putObject("serverInfo").put("name", "smithery-placeholder").put("version", "0.0.0");
            }
            return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"result\":" + JSON.writeValueAsString(result) + "}";
        } catch (Exception e) {
            return null;
        }
    }

    private SmitheryMcpFilter() {
    }
}
