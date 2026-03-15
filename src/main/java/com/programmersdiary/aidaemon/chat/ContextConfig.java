package com.programmersdiary.aidaemon.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ContextConfig {

    private final String systemInstructions;
    private final int charsLimit;
    private final double personalMemoryRatio;

    public ContextConfig(
            @Value("${aidaemon.system-instructions:}") String systemInstructions,
            @Value("${aidaemon.context-window.chars-limit:${aidaemon.chars-context-window:0}}") int charsLimit,
            @Value("${aidaemon.context-window.personal-memory-ratio:0}") double personalMemoryRatio) {
        this.systemInstructions = systemInstructions != null ? systemInstructions : "";
        this.charsLimit = charsLimit;
        this.personalMemoryRatio = Math.max(0, Math.min(1, personalMemoryRatio));
    }

    public String systemInstructions() {
        return systemInstructions;
    }

    public int charsLimit() {
        return charsLimit;
    }

    public int conversationLimit() {
        return (int) (charsLimit * (1 - personalMemoryRatio));
    }

    public int personalMemoryLimit() {
        return (int) (charsLimit * personalMemoryRatio);
    }
}
