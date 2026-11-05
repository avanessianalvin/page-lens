package com.pagelens;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.pagelens.mcp.McpProperties;

/**
 * stdio MCP server launched by Claude Desktop / Claude Code (see docs/SETUP.md, "Connect Claude").
 *
 * <p><b>stdout carries the MCP protocol. Nothing else may ever write to it</b>: no banner, no console
 * logging, no {@code System.out.println}. Logs go to stderr and the files in {@code pagelens.log-dir}.
 *
 * <p>The process lives as long as the client keeps stdin open ({@link com.pagelens.mcp.StdioRunner}). It never
 * runs scheduled syncs: only app-web does.
 */
@SpringBootApplication
@EnableConfigurationProperties(McpProperties.class)
public class PageLensMcpApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(PageLensMcpApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.setWebApplicationType(WebApplicationType.NONE);
        var context = app.run(args); // returns once the MCP client disconnects
        System.exit(SpringApplication.exit(context));
    }
}
