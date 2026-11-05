package com.pagelens.core.instagram;

import java.util.regex.Pattern;

/**
 * Masks secrets in URLs, query strings and messages before anything is logged or stored.
 * Covers every secret-bearing parameter PageLens sends to Meta.
 */
public final class SecretMasker {

    private static final Pattern SECRET_PARAM = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(access_token|client_secret|fb_exchange_token|input_token|code)=([^&\\s\"']+)");

    private SecretMasker() {
    }

    public static String mask(String text) {
        return text == null ? null : SECRET_PARAM.matcher(text).replaceAll("$1=***");
    }
}
