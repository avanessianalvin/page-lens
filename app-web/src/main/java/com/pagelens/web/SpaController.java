package com.pagelens.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Vue app's index.html for client-side routes, so deep links and browser refreshes work.
 * The built frontend lives in {@code classpath:/static} (Vite writes it there; see frontend/vite.config.ts).
 */
@Controller
public class SpaController {

    private static final ClassPathResource INDEX = new ClassPathResource("static/index.html");

    @GetMapping({"/", "/login", "/accounts", "/accounts/**", "/posts", "/posts/**", "/logs", "/logs/**"})
    public ResponseEntity<?> index() {
        if (!INDEX.exists()) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("""
                    PageLens API is running, but the dashboard has not been built.
                    Build it with:  cd frontend && npm install && npm run build
                    Or develop with: cd frontend && npm run dev   (then open http://localhost:5173)
                    """);
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(INDEX);
    }
}
