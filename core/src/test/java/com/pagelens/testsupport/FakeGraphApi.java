package com.pagelens.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * In-process stand-in for graph.instagram.com / graph.facebook.com / api.instagram.com, built on the JDK's
 * HttpServer (no extra test dependency). Routes are exact {@code METHOD path} matches. Unrouted requests get
 * Meta's "unsupported request" error (code 100), like the real API.
 */
public final class FakeGraphApi implements AutoCloseable {

    public record Request(String method, String path, Map<String, String> query, String body) {
    }

    public record Response(int status, String body, Map<String, String> headers) {
        public static Response json(String body) {
            return new Response(200, body, Map.of());
        }

        public static Response error(int status, int code, String message) {
            return new Response(status, "{\"error\":{\"message\":\"" + message + "\",\"type\":\"OAuthException\",\"code\":"
                    + code + ",\"fbtrace_id\":\"test\"}}", Map.of());
        }
    }

    private final HttpServer server;
    private final Map<String, Function<Request, Response>> routes = new ConcurrentHashMap<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

    private FakeGraphApi(HttpServer server) {
        this.server = server;
    }

    public static FakeGraphApi start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeGraphApi fake = new FakeGraphApi(server);
            server.createContext("/", fake::handle);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeGraphApi on(String method, String path, Function<Request, Response> handler) {
        routes.put(method + " " + path, handler);
        return this;
    }

    public FakeGraphApi onJson(String method, String path, String body) {
        return on(method, path, r -> Response.json(body));
    }

    public FakeGraphApi onFixture(String method, String path, String fixture) {
        String body = fixture(fixture).replace("${BASE}", url());
        return on(method, path, r -> Response.json(body));
    }

    public void reset() {
        routes.clear();
        requests.clear();
    }

    public List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public List<Request> requests(String path) {
        return requests().stream().filter(r -> r.path().equals(path)).toList();
    }

    /** Loads {@code src/test/resources/fixtures/graph/<name>}. {@code ${BASE}} is replaced with this server's URL. */
    public static String fixture(String name) {
        try (InputStream in = FakeGraphApi.class.getResourceAsStream("/fixtures/graph/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Request req = new Request(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                parse(ex.getRequestURI().getRawQuery()), body);
        requests.add(req);
        Function<Request, Response> route = routes.get(req.method() + " " + req.path());
        Response resp = route != null ? route.apply(req)
                : Response.error(400, 100, "Unsupported " + req.method() + " request " + req.path());
        byte[] bytes = resp.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        resp.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.sendResponseHeaders(resp.status(), bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    public static Map<String, String> parse(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
