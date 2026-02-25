package com.programmersdiary.aidaemon.skills;

import com.programmersdiary.aidaemon.mcp.McpServerConfig;
import com.programmersdiary.aidaemon.mcp.McpService;
import com.programmersdiary.aidaemon.mcp.McpTransportType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

@Component
@ConditionalOnBean(SmitheryConnectClient.class)
public class SmitheryMcpTool {

    private static final String SERVER_BASE = "https://server.smithery.ai/";
    private static final String CONNECT_MCP_PATH = "/mcp";

    private final SmitheryConnectClient connectClient;
    private final McpService mcpService;
    private final String namespace;
    private final String apiKey;

    public SmitheryMcpTool(SmitheryConnectClient connectClient,
                           McpService mcpService,
                           @Value("${aidaemon.smithery-mcp.namespace}") String namespace,
                           @Value("${aidaemon.smithery-mcp.api-key}") String apiKey) {
        this.connectClient = connectClient;
        this.mcpService = mcpService;
        this.namespace = namespace != null ? namespace : "";
        this.apiKey = apiKey != null ? apiKey : "";
    }

    @Tool(description = "Add a Smithery-hosted MCP server to the framework. Provide the Smithery server page URL (e.g. https://smithery.ai/servers/googlecalendar) or the server slug (e.g. googlecalendar). If the server requires OAuth, returns an authorization URL for the user to open; after authorizing, ask the user to reload MCPs.")
    public String addSmitheryMcp(
            @ToolParam(description = "Smithery server URL like https://smithery.ai/servers/googlecalendar or the server slug e.g. googlecalendar") String smitheryServerUrlOrSlug) {
        if (namespace.isEmpty() || apiKey.isEmpty()) {
            return "Error: Smithery MCP is not fully configured (namespace or api-key missing).";
        }
        var slug = parseSlug(smitheryServerUrlOrSlug);
        if (slug == null || slug.isBlank()) {
            return "Error: Could not parse server slug from: " + smitheryServerUrlOrSlug + ". Use a URL like https://smithery.ai/servers/googlecalendar or the slug 'googlecalendar'.";
        }
        var mcpUrl = SERVER_BASE + slug;
        try {
            var result = connectClient.createConnection(namespace, mcpUrl);
            var connectUrl = "https://api.smithery.ai/connect/" + namespace + "/" + result.connectionId() + CONNECT_MCP_PATH;
            var config = new McpServerConfig(
                    result.connectionId(),
                    McpTransportType.REMOTE,
                    connectUrl,
                    Map.of("Authorization", "Bearer " + apiKey),
                    null,
                    null,
                    null);
            mcpService.writeServerConfig(result.connectionId(), config);
            if ("connected".equals(result.state())) {
                mcpService.loadAll();
                return "Smithery MCP server '" + result.connectionId() + "' added and connected. Tools are available.";
            }
            if ("auth_required".equals(result.state()) && result.authorizationUrl() != null) {
                return "MCP server '" + result.connectionId() + "' added. Authorization required. Open this URL in your browser to sign in, then reload MCPs: " + result.authorizationUrl();
            }
            return "MCP server '" + result.connectionId() + "' config saved. State: " + result.state() + ". Reload MCPs after authorizing if needed.";
        } catch (Exception e) {
            return "Error adding Smithery MCP: " + e.getMessage();
        }
    }

    private static String parseSlug(String input) {
        if (input == null || input.isBlank()) return null;
        var trimmed = input.trim();
        if (trimmed.startsWith("https://smithery.ai/servers/")) {
            var path = trimmed.substring("https://smithery.ai/servers/".length());
            var slash = path.indexOf('/');
            return slash >= 0 ? path.substring(0, slash) : path;
        }
        if (trimmed.contains("/")) return null;
        return trimmed;
    }
}
