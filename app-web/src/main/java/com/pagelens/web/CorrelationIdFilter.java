package com.pagelens.web;

import java.io.IOException;
import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pagelens.core.logging.CorrelationId;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Puts a correlation id on every request's log lines and echoes it back in {@code X-Correlation-Id}.
 * A well-formed id sent by the client (e.g. the Vue app) is reused; anything else is replaced.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        try (var ignored = (incoming != null && SAFE_ID.matcher(incoming).matches())
                ? CorrelationId.use(incoming)
                : CorrelationId.open("http")) {
            response.setHeader(HEADER, CorrelationId.current());
            chain.doFilter(request, response);
        }
    }
}
