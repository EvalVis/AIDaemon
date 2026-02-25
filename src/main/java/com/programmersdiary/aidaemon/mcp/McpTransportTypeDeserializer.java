package com.programmersdiary.aidaemon.mcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

public class McpTransportTypeDeserializer extends JsonDeserializer<McpTransportType> {

    @Override
    public McpTransportType deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String value = p.getText();
        return McpTransportType.fromString(value);
    }
}
