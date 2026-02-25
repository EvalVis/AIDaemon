package com.programmersdiary.aidaemon.mcp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum McpTransportType {
    REMOTE, SSE, LOCAL;

    @JsonCreator
    public static McpTransportType fromString(String value) {
        if (value == null || value.isBlank()) return LOCAL;
        return switch (value.toUpperCase()) {
            case "REMOTE" -> REMOTE;
            case "SSE" -> SSE;
            case "LOCAL" -> LOCAL;
            default -> LOCAL;
        };
    }

    @JsonValue
    public String toValue() {
        return name();
    }
}
