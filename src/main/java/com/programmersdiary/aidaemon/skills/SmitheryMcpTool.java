package com.programmersdiary.aidaemon.skills;

import com.programmersdiary.aidaemon.mcp.McpServerConfig;
import com.programmersdiary.aidaemon.mcp.McpService;
import com.programmersdiary.aidaemon.mcp.McpTransportType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;
import java.util.stream.Collectors;

@Component
@ConditionalOnBean(SmitheryConnectClient.class)
public class SmitheryMcpTool {

    private static final String SERVER_BASE = "https://server.smithery.ai/";
    private static final String CONNECT_MCP_PATH = "/mcp";
    private static final int SEARCH_PAGE_SIZE = 10;

    private final SmitheryConnectClient connectClient;
    private final McpService mcpService;
    private final SmitheryRegistryClient registryClient;
    private final String namespace;
    private final String apiKey;

    public SmitheryMcpTool(SmitheryConnectClient connectClient,
                           McpService mcpService,
                           @Autowired(required = false) SmitheryRegistryClient registryClient,
                           @Value("${aidaemon.smithery-mcp.namespace}") String namespace,
                           @Value("${aidaemon.smithery-mcp.api-key}") String apiKey) {
        this.connectClient = connectClient;
        this.mcpService = mcpService;
        this.registryClient = registryClient;
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

    @Tool(description = "Search the Smithery MCP server registry by keyword (e.g. gmail, calendar, notion). Returns one page of results; use page parameter for more. Use addSmitheryMcp with the server URL or slug to add one.")
    public String searchSmitheryMcp(
            @ToolParam(description = "Search query, e.g. gmail, calendar, email, notion") String query,
            @ToolParam(description = "Page number (1-based). Omit for first page.") Integer page) {
        if (registryClient == null) {
            return "Search is not available (registry client not configured).";
        }
        if (query == null || query.isBlank()) {
            return "Provide a search query (e.g. gmail, calendar).";
        }
        int pageNum = page == null || page < 1 ? 1 : page;
        try {
            var response = registryClient.searchServers(query.trim(), pageNum, SEARCH_PAGE_SIZE);
            if (response == null || response.servers() == null || response.servers().length == 0) {
                return "No MCP servers found for \"" + query + "\" on page " + pageNum + ".";
            }
            var pagination = response.pagination();
            var lines = java.util.stream.IntStream.range(0, response.servers().length)
                    .mapToObj(i -> {
                        var s = response.servers()[i];
                        return (i + 1) + ". " + s.displayName() + " — " + (s.description() != null ? s.description() : "")
                                + " Slug: " + (s.qualifiedName() != null ? s.qualifiedName() : s.slug())
                                + (s.homepage() != null ? " | " + s.homepage() : "");
                    })
                    .collect(Collectors.joining("\n"));
            var pageHint = pagination.totalPages() > 1
                    ? " Page " + pagination.currentPage() + " of " + pagination.totalPages() + ". For more, call searchSmitheryMcp with the same query and page=" + (pageNum + 1) + "."
                    : "";
            return "Found " + pagination.totalCount() + " server(s)." + pageHint + "\n" + lines
                    + "\nTo add one, use addSmitheryMcp with the slug or homepage URL.";
        } catch (Exception e) {
            return "Search failed: " + e.getMessage();
        }
    }

    private static String parseSlug(String input) {
        if (input == null || input.isBlank()) return null;
        var trimmed = input.trim();
        if (trimmed.startsWith("https://smithery.ai/servers/")) {
            return trimmed.substring("https://smithery.ai/servers/".length()).split("\\?")[0];
        }
        return trimmed;
    }
}
