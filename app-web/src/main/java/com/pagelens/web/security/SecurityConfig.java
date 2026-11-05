package com.pagelens.web.security;

import java.io.IOException;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Single-user form login for the dashboard (plan section 5): bcrypt password, session cookie, CSRF protection
 * (SPA cookie mode: the Vue app echoes the {@code XSRF-TOKEN} cookie in an {@code X-XSRF-TOKEN} header), and
 * login rate limiting. {@code /api/**} answers 401 instead of redirecting, so the SPA can show its login page.
 */
@Configuration
@EnableConfigurationProperties(WebUserProperties.class)
public class SecurityConfig {

    static final String LOGIN_URL = "/api/auth/login";
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(WebUserProperties props) {
        String hash = props.passwordHash();
        if (hash == null || !hash.matches("^\\$2[aby]\\$\\d{2}\\$.{53}$")) {
            throw new IllegalStateException("""
                    PAGELENS_WEB_PASSWORD_HASH is missing or not a bcrypt hash. Generate one with:
                      java -jar app-web/target/pagelens-web.jar --hash-password
                    and add the printed line to .env (see docs/SETUP.md).""");
        }
        String username = props.username() == null || props.username().isBlank() ? "admin" : props.username();
        return new InMemoryUserDetailsManager(User.withUsername(username).password(hash).roles("OWNER").build());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, LoginAttemptLimiter limiter) {
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();
        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/**", "/auth/**", "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").authenticated()
                        // Everything else is the SPA shell and its static assets; data only comes from /api.
                        .anyRequest().permitAll())
                .formLogin(f -> f
                        .loginPage("/login")
                        .loginProcessingUrl(LOGIN_URL)
                        .successHandler((req, res, auth) -> {
                            limiter.succeeded(client(req));
                            log.info("Dashboard login succeeded for {}", auth.getName());
                            json(res, HttpStatus.OK, "{\"username\":\"" + auth.getName() + "\"}");
                        })
                        .failureHandler((req, res, ex) -> {
                            limiter.failed(client(req));
                            log.warn("Dashboard login failed from {}", client(req));
                            json(res, HttpStatus.UNAUTHORIZED, "{\"error\":\"Invalid username or password\"}");
                        }))
                .logout(l -> l
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpStatus.NO_CONTENT.value())))
                .csrf(c -> c.spa())
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), path.matcher("/api/**")))
                .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
                .addFilterBefore(new LockoutFilter(limiter), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Rejects login attempts from a locked-out client before the password is even checked. */
    static final class LockoutFilter extends OncePerRequestFilter {
        private final LoginAttemptLimiter limiter;

        LockoutFilter(LoginAttemptLimiter limiter) {
            this.limiter = limiter;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !("POST".equals(request.getMethod()) && LOGIN_URL.equals(request.getRequestURI()));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            Instant until = limiter.lockedUntil(client(req));
            if (until != null) {
                res.setHeader("Retry-After", String.valueOf(Math.max(1, until.getEpochSecond() - Instant.now().getEpochSecond())));
                json(res, HttpStatus.TOO_MANY_REQUESTS, "{\"error\":\"Too many failed logins. Try again later.\"}");
                return;
            }
            chain.doFilter(req, res);
        }
    }

    private static String client(HttpServletRequest req) {
        return req.getRemoteAddr();
    }

    private static void json(HttpServletResponse res, HttpStatus status, String body) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(body);
    }
}
