package com.pagelens;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.pagelens.testsupport.PostgresTestcontainer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/**
 * Guards the most important MCP rule: the process must not write anything to stdout,
 * because stdout carries the MCP protocol.
 */
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class PageLensMcpApplicationTest {

    @Test
    void startupWritesNothingToStdout(CapturedOutput output) {
        SpringApplication app = new SpringApplication(PageLensMcpApplication.class, PostgresTestcontainer.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setMainApplicationClass(PageLensMcpApplication.class);
        try (var ctx = app.run("--pagelens.log-dir=target/test-logs")) {
            assertThat(ctx.isActive()).isTrue();
        }

        assertThat(output.getErr())
                .contains("Started PageLensMcpApplication")
                .doesNotContain("|-INFO in ch.qos.logback"); // no logback status dump
        assertThat(output.getOut()).isEmpty();
    }
}
