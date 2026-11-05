package com.pagelens.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

/** MCP resources (metric glossary, connected accounts) and prompts (weekly performance review). */
@Component
public class PageLensResources {

    static final String GLOSSARY_URI = "pagelens://glossary/metrics";
    static final String ACCOUNTS_URI = "pagelens://accounts";

    private final PageLensTools tools;
    private final String glossary;

    public PageLensResources(PageLensTools tools) {
        this.tools = tools;
        try (InputStream in = getClass().getResourceAsStream("/mcp/metric-glossary.md")) {
            this.glossary = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String glossary() {
        return glossary;
    }

    public List<SyncResourceSpecification> resources() {
        Resource glossaryRes = Resource.builder().uri(GLOSSARY_URI).name("metric-glossary")
                .title("PageLens metric glossary")
                .description("What each Instagram metric means, why values can be missing, and small-sample rules. "
                        + "Read before interpreting numbers.")
                .mimeType("text/markdown").build();
        Resource accountsRes = Resource.builder().uri(ACCOUNTS_URI).name("accounts").title("Connected accounts")
                .description("Connected Instagram accounts with status and last sync (same as list_accounts).")
                .mimeType("application/json").build();
        return List.of(
                new SyncResourceSpecification(glossaryRes, (exchange, req) -> new ReadResourceResult(
                        List.of(new TextResourceContents(GLOSSARY_URI, "text/markdown", glossary)))),
                new SyncResourceSpecification(accountsRes, (exchange, req) -> new ReadResourceResult(
                        List.of(new TextResourceContents(ACCOUNTS_URI, "application/json", tools.accountsJson())))));
    }

    public List<SyncPromptSpecification> prompts() {
        Prompt weekly = new Prompt("weekly_performance_review", "Weekly performance review",
                "A structured review of the last 7 days for one Instagram account, using PageLens tools.",
                List.of(new PromptArgument("account", "Account username or id (optional with one account)", false)));
        return List.of(new SyncPromptSpecification(weekly, (exchange, req) -> {
            Object acc = req.arguments() == null ? null : req.arguments().get("account");
            String account = acc == null || acc.toString().isBlank() ? "the connected account" : acc.toString();
            String text = """
                    Write a weekly Instagram performance review for %s using the PageLens tools.

                    1. Call get_sync_status and say how fresh the data is (warn if the last sync is over a day old).
                    2. Call get_account_overview: followers and their 7-day change, reach and views vs the previous
                       week, posts published, average engagement rate.
                    3. Call get_top_posts and get_bottom_posts with period last_7_days (metric reach, then
                       engagement_rate). Explain what the best posts have in common.
                    4. Call compare_formats with period last_30_days. Which format works best right now?
                    5. Call get_best_posting_times with period last_90_days. Suggest 2-3 posting slots, and say how
                       many posts back each one.
                    6. Finish with 3 concrete, specific suggestions for next week.

                    Rules: read the pagelens://glossary/metrics resource first. Missing values mean Instagram did
                    not return them, never zero. Call out anything flagged smallSample. Quote numbers exactly as
                    returned. Do not guess metrics that aren't in the data.
                    """.formatted(account);
            return new GetPromptResult("Weekly review for " + account,
                    List.of(new PromptMessage(Role.USER, new TextContent(text))));
        }));
    }
}
