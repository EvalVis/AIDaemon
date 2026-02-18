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

    @PostMapping("/{name}")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> addServer(@PathVariable String name, @RequestBody McpServerConfig config) {
        mcpService.addServer(name, config);
        return Map.of("name", name, "status", "connected");
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeServer(@PathVariable String name) {
        if (!mcpService.removeServer(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        mcpService.loadAll();
        return Map.of("servers", mcpService.getConnectedServers());
    }
}
