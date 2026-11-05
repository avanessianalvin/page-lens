package com.pagelens.mcp;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Serves MCP on the process's real stdin/stdout and blocks until the client disconnects. It uses the raw file
 * descriptors rather than {@code System.out}, so a stray {@code System.out.println} elsewhere can't interleave
 * with protocol frames inside the same buffer. Protocol bytes are the only thing ever written to fd 1.
 */
@Component
@ConditionalOnProperty(name = "pagelens.mcp.stdio-enabled", havingValue = "true", matchIfMissing = true)
public class StdioRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StdioRunner.class);

    private final PageLensMcpServer server;

    public StdioRunner(PageLensMcpServer server) {
        this.server = server;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var running = server.start(new FileInputStream(FileDescriptor.in), new FileOutputStream(FileDescriptor.out));
        log.info("PageLens MCP server '{}' {} ready on stdio", PageLensMcpServer.NAME, PageLensMcpServer.version());
        running.awaitClose();
        log.info("MCP client disconnected (stdin closed); shutting down");
        running.server().closeGracefully();
    }
}
