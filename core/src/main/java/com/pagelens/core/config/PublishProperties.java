package com.pagelens.core.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Publishing tuning ({@code pagelens.publish.*}): container status polling and timeouts. */
@ConfigurationProperties(prefix = "pagelens.publish")
public record PublishProperties(Duration pollInitial, Duration pollMax, Duration imageTimeout, Duration videoTimeout) {
}
