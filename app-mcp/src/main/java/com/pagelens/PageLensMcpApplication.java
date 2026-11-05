package com.pagelens;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * stdio MCP server launched by Claude Desktop / Claude Code.
 *
 * <p><b>stdout carries the MCP protocol. Nothing else may ever write to it</b>: no banner, no console
 * logging, no {@code System.out.println}. Logs go to stderr and the files in {@code pagelens.log-dir}.
 *
 * <p>Phase 0: boots the shared core (database, Flyway, logging) and exits. The MCP SDK server and tools
 * are added in Phase 2.
 */
@SpringBootApplication
public class PageLensMcpApplication {

    private static final Logger log = LoggerFactory.getLogger(PageLensMcpApplication.class);

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(PageLensMcpApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var ignored = app.run(args)) {
            log.info("PageLens MCP foundation started; MCP tools are not implemented yet (Phase 2)");
        }
    }
}
