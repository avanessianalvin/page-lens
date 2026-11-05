package com.pagelens.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP server settings ({@code pagelens.mcp.*}).
 *
 * @param stdioEnabled      serve MCP on stdin/stdout at startup (off only in tests)
 * @param syncToolEnabled   expose {@code sync_now}. It only reads from Instagram, but it spends rate budget.
 * @param writeToolsEnabled expose reply_to_comment, hide_comment and publish_post. They also need the global
 *                          write-actions switch: both must be on (a second, Claude-specific opt-in).
 */
@ConfigurationProperties(prefix = "pagelens.mcp")
public record McpProperties(boolean stdioEnabled, boolean syncToolEnabled, boolean writeToolsEnabled) {
}
