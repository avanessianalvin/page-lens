package com.pagelens.core.instagram;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Core's private JSON mapper for Graph API payloads. Deliberately <em>not</em> a {@code JsonMapper} bean:
 * exposing one would make Spring Boot back off its own auto-configured mapper in app-web.
 */
@Component
public class GraphJson {

    private final JsonMapper mapper = JsonMapper.builder().build();

    public JsonMapper mapper() {
        return mapper;
    }
}
