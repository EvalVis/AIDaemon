package com.programmersdiary.aidaemon.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpServerConfig(
        @JsonProperty("name") String name,
        @JsonProperty("type") McpTransportType type,
        @JsonProperty("url") String url,
        @JsonProperty("headers") Map<String, String> headers,
        @JsonProperty("command") String command,
        @JsonProperty("args") List<String> args,
        @JsonProperty("env") Map<String, String> env) {
}
