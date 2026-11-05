package com.pagelens.web.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The dashboard's single user ({@code pagelens.web.*}).
 *
 * @param username     login name (default {@code admin})
 * @param passwordHash bcrypt hash; generate with {@code java -jar pagelens-web.jar --hash-password}
 */
@ConfigurationProperties(prefix = "pagelens.web")
public record WebUserProperties(String username, String passwordHash) {
}
