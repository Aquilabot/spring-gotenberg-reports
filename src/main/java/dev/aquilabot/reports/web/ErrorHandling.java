package dev.aquilabot.reports.web;

import dev.aquilabot.reports.gotenberg.GotenbergException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps failures to RFC 9457 problem responses. */
@RestControllerAdvice
class ErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandling.class);

    @ExceptionHandler
    ProblemDetail gotenberg(GotenbergException e) {
        log.atWarn().addKeyValue("gotenbergStatus", e.status()).log(e.getMessage());
        HttpStatus status = e.status() == 0 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                e.status() == 0 ? "The PDF service is not reachable." : "The PDF service could not render the document.");
        problem.setTitle("PDF rendering failed");
        problem.setProperty("gotenbergStatus", e.status());
        return problem;
    }

    @ExceptionHandler
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
