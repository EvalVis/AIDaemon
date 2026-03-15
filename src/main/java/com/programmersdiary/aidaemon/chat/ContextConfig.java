package com.programmersdiary.aidaemon.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ContextConfig {

    private final String systemInstructions;
    private final int charsLimit;

    public ContextConfig(
            @Value("${aidaemon.system-instructions:}") String systemInstructions,
            @Value("${aidaemon.context-window.chars-limit:${aidaemon.chars-context-window:0}}") int charsLimit) {
        this.systemInstructions = systemInstructions != null ? systemInstructions : "";
        this.charsLimit = charsLimit;
    }

    public String systemInstructions() {
        return systemInstructions;
    }

    public int charsLimit() {
        return charsLimit;
    }
}
