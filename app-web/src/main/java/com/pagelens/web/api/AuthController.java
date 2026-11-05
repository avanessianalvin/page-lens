package com.pagelens.web.api;

import java.security.Principal;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/** Session probe for the SPA. Calling it also issues the XSRF-TOKEN cookie the SPA needs before logging in. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record Session(boolean authenticated, String username) {
    }

    @GetMapping("/session")
    public Session session(Principal principal, HttpServletRequest request) {
        // Read from the request (not a method parameter, which springdoc would document as an API input).
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken csrf) {
            csrf.getToken(); // materialise the token so the cookie is written on this response
        }
        return new Session(principal != null, principal == null ? null : principal.getName());
    }
}
