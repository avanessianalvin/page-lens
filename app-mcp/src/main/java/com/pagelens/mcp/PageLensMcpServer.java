package com.pagelens.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the {@code pagelens} MCP server on any pair of streams: System.in/System.out in production, pipes in
 * tests. {@link Running#awaitClose()} returns when the client closes its end (stdin EOF), so the process exits
 * cleanly when Claude stops it.
 */
@Component
public class PageLensMcpServer {

    static final String NAME = "pagelens";

    static final String INSTRUCTIONS = """
            PageLens holds a local copy of the owner's Instagram Business/Creator account data: posts, insight
            snapshots over time, comments, daily account metrics and audience demographics. Read tools never call
            Instagram. Start with list_accounts, then get_account_overview. Read pagelens://glossary/metrics before
            interpreting numbers: a missing value means Instagram did not return it, never zero. Check
            get_sync_status to see how fresh the data is. Mention smallSample flags when relying on those numbers.
            """;

    private final PageLensTools tools;
    private final PageLensResources resources;

    public PageLensMcpServer(PageLensTools tools, PageLensResources resources) {
        this.tools = tools;
        this.resources = resources;
    }

    public record Running(McpSyncServer server, CountDownLatch closed, CountDownLatch release, PageLensTools tools) {
        /**
         * Blocks until the client closes its end, waits for in-flight tool calls to answer (max 15 s), and only
         * then lets the transport see end-of-file. The SDK closes its output as soon as it reads EOF, so passing
         * EOF through earlier would drop answers to requests sent just before the client closed.
         */
        public void awaitClose() throws InterruptedException {
            closed.await();
            tools.awaitIdle(Duration.ofSeconds(15));
            release.countDown();
        }
    }

    public Running start(InputStream in, OutputStream out) {
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(JsonMapper.builder().build()),
                new EofSignallingInputStream(in, closed, release), out);
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(NAME, version())
                .instructions(INSTRUCTIONS)
                .requestTimeout(Duration.ofMinutes(2))
                .capabilities(ServerCapabilities.builder().tools(false).resources(false, false).prompts(false).build())
                .tools(tools.specifications())
                .resources(resources.resources())
                .prompts(resources.prompts())
                .build();
        return new Running(server, closed, release, tools);
    }

    static String version() {
        String v = PageLensMcpServer.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    /**
     * Signals {@code closed} when the underlying stream reaches end-of-file, then holds that EOF back from the
     * reader (the SDK transport) until {@code release} opens.
     */
    static final class EofSignallingInputStream extends FilterInputStream {
        private final CountDownLatch closed;
        private final CountDownLatch release;

        EofSignallingInputStream(InputStream in, CountDownLatch closed, CountDownLatch release) {
            super(in);
            this.closed = closed;
            this.release = release;
        }

        @Override
        public int read() throws IOException {
            return signal(super.read());
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return signal(super.read(b, off, len));
        }

        private int signal(int n) {
            if (n < 0) {
                closed.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            closed.countDown();
            super.close();
        }
    }
}
