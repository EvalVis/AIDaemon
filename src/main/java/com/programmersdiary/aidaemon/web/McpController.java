package com.programmersdiary.aidaemon.web;

import com.programmersdiary.aidaemon.mcp.McpServerConfig;
import com.programmersdiary.aidaemon.mcp.McpService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mcps")
public class McpController {

    private final McpService mcpService;

    public McpController(McpService mcpService) {
        this.mcpService = mcpService;
    }

    @GetMapping
    public List<String> listServers() {
        return mcpService.getConnectedServers();
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        mcpService.loadAll();
        return Map.of("servers", mcpService.getConnectedServers());
    }
}
