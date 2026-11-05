package com.pagelens.web.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.pagelens.core.instagram.GraphApiException;
import com.pagelens.core.instagram.SecretMasker;
import com.pagelens.core.sync.SyncService;

/** Maps exceptions to RFC 9457 problem responses. Messages are secret-masked before they leave the server. */
@RestControllerAdvice(basePackages = "com.pagelens.web.api")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(com.pagelens.core.write.WriteGuard.WriteActionsDisabledException.class)
    ProblemDetail writesDisabled(com.pagelens.core.write.WriteGuard.WriteActionsDisabledException e) {
        return problem(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "The file is over Instagram's 300 MB video limit");
    }

    @ExceptionHandler(SyncService.AlreadyRunningException.class)
    ProblemDetail conflict(SyncService.AlreadyRunningException e) {
        return problem(HttpStatus.CONFLICT, e.getMessage());
    }

    /** Configuration problems (missing Meta app id, encryption key...) and invalid state. */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail state(IllegalStateException e) {
        log.warn("Request failed: {}", SecretMasker.mask(e.getMessage()));
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(GraphApiException.class)
    ProblemDetail graph(GraphApiException e) {
        log.warn("Instagram API error ({}): {}", e.kind(), e.getMessage());
        HttpStatus status = e.kind() == GraphApiException.Kind.RATE_LIMITED ? HttpStatus.TOO_MANY_REQUESTS
                : HttpStatus.BAD_GATEWAY;
        ProblemDetail p = problem(status, "Instagram API: " + e.getMessage());
        p.setProperty("kind", e.kind().name());
        p.setProperty("errorCode", e.errorCode());
        return p;
    }

    private static ProblemDetail problem(HttpStatus status, String message) {
        return ProblemDetail.forStatusAndDetail(status, SecretMasker.mask(message));
    }
}
