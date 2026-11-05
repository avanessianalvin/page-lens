package com.pagelens.core.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Graph API HTTP behaviour ({@code pagelens.http.*}).
 *
 * @param slowDownAtPercent Meta usage percentage at which calls are spaced out
 * @param stopAtPercent     usage percentage at which calls stop until access is regained
 */
@ConfigurationProperties(prefix = "pagelens.http")
public record HttpProperties(Duration connectTimeout, Duration requestTimeout, int maxAttempts,
                             Duration initialBackoff, int slowDownAtPercent, int stopAtPercent) {
}
