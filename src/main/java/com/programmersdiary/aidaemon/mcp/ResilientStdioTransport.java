package com.programmersdiary.aidaemon.mcp;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.json.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

public class ResilientStdioTransport implements McpClientTransport {

    private static final Logger log = LoggerFactory.getLogger(ResilientStdioTransport.class);

    private final ServerParameters params;
    private final McpJsonMapper jsonMapper;
    private final Sinks.Many<McpSchema.JSONRPCMessage> inboundSink;
    private final Sinks.Many<McpSchema.JSONRPCMessage> outboundSink;
    private final Sinks.Many<String> errorSink;
    private final Scheduler inboundScheduler;
    private final Scheduler outboundScheduler;
    private final Scheduler errorScheduler;
    private Process process;
    private volatile boolean isClosing;
    private Consumer<String> stdErrorHandler;

    public ResilientStdioTransport(ServerParameters params, McpJsonMapper jsonMapper) {
        this.params = params;
        this.jsonMapper = jsonMapper;
        this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
        this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();
        this.errorSink = Sinks.many().unicast().onBackpressureBuffer();
        this.inboundScheduler = Schedulers.fromExecutorService(
                Executors.newSingleThreadExecutor(), "inbound");
        this.outboundScheduler = Schedulers.fromExecutorService(
                Executors.newSingleThreadExecutor(), "outbound");
        this.errorScheduler = Schedulers.fromExecutorService(
                Executors.newSingleThreadExecutor(), "error");
        this.stdErrorHandler = msg -> log.info("STDERR Message received: {}", msg);
    }

    @Override
    public Mono<Void> connect(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.<Void>fromRunnable(() -> doConnect(handler))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void doConnect(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        log.info("MCP server starting.");
        subscribeToInbound(handler);
        subscribeToErrors();

        var command = new ArrayList<String>();
        command.add(params.getCommand());
        command.addAll(params.getArgs());

        var pb = new ProcessBuilder();
        pb.command(command);
        pb.environment().putAll(params.getEnv());

        try {
            process = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start process: " + command, e);
        }

        if (process.getInputStream() == null || process.getOutputStream() == null) {
            process.destroy();
            throw new RuntimeException("Process input or output stream is null");
        }

        startInboundProcessing();
        startOutboundProcessing();
        startErrorProcessing();
        log.info("MCP server started");
    }

    private void subscribeToInbound(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        inboundSink.asFlux()
                .flatMap(msg -> Mono.just(msg).transform(handler))
                .subscribe();
    }

    private void subscribeToErrors() {
        errorSink.asFlux()
                .subscribe(msg -> stdErrorHandler.accept(msg));
    }

    private void startInboundProcessing() {
        inboundScheduler.schedule(this::processInbound);
    }

    private void processInbound() {
        try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!isClosing && (line = reader.readLine()) != null) {
                try {
                    var message = McpSchema.deserializeJsonRpcMessage(jsonMapper, line);
                    var result = inboundSink.tryEmitNext(message);
                    if (!result.isSuccess() && !isClosing) {
                        log.error("Failed to enqueue inbound message: {}", message);
                        break;
                    }
                } catch (Exception e) {
                    if (!isClosing) {
                        log.warn("Skipping non-JSON line on stdout: {}", line);
                    }
                }
            }
        } catch (IOException e) {
            if (!isClosing) {
                log.error("Error reading from input stream", e);
            }
        } finally {
            isClosing = true;
            inboundSink.tryEmitComplete();
        }
    }

    private void startOutboundProcessing() {
        outboundScheduler.schedule(() -> {
            outboundSink.asFlux()
                    .handle((message, sink) -> {
                        try {
                            var json = jsonMapper.writeValueAsString(message);
                            var writer = new PrintWriter(
                                    new OutputStreamWriter(
                                            process.getOutputStream(), StandardCharsets.UTF_8),
                                    true);
                            writer.println(json);
                            writer.flush();
                        } catch (Exception e) {
                            if (!isClosing) {
                                log.error("Error writing message", e);
                            }
                        }
                    })
                    .subscribe();
        });
    }

    private void startErrorProcessing() {
        errorScheduler.schedule(() -> {
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!isClosing && (line = reader.readLine()) != null) {
                    var result = errorSink.tryEmitNext(line);
                    if (!result.isSuccess() && !isClosing) {
                        log.error("Error processing error message", new RuntimeException());
                    }
                }
            } catch (IOException e) {
                if (!isClosing) {
                    log.error("Error reading stderr", e);
                }
            } finally {
                isClosing = true;
                errorSink.tryEmitComplete();
            }
        });
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        var result = outboundSink.tryEmitNext(message);
        if (result.isFailure()) {
            return Mono.error(new RuntimeException("Failed to enqueue message"));
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> closeGracefully() {
        isClosing = true;
        return Mono.fromRunnable(() -> {
            inboundSink.tryEmitComplete();
            outboundSink.tryEmitComplete();
            errorSink.tryEmitComplete();
        })
        .then(Mono.delay(Duration.ofMillis(100)))
        .doFinally(signal -> {
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            inboundScheduler.dispose();
            outboundScheduler.dispose();
            errorScheduler.dispose();
        })
        .then();
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return jsonMapper.convertValue(data, typeRef);
    }

    public void setStdErrorHandler(Consumer<String> errorHandler) {
        this.stdErrorHandler = errorHandler;
    }
}
