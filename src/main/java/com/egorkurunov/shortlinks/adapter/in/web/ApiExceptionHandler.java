package com.egorkurunov.shortlinks.adapter.in.web;

import com.egorkurunov.shortlinks.domain.error.CodeConflictException;
import com.egorkurunov.shortlinks.domain.error.GenerationExhaustedException;
import com.egorkurunov.shortlinks.domain.error.InvalidInputException;
import com.egorkurunov.shortlinks.domain.error.LinkNotFoundException;
import com.egorkurunov.shortlinks.domain.error.StorageUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({
        InvalidInputException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ProblemDetail> invalid(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid URL, code, or request body", request);
    }

    @ExceptionHandler(LinkNotFoundException.class)
    public ResponseEntity<ProblemDetail> missing(HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Link not found", request);
    }

    @ExceptionHandler(CodeConflictException.class)
    public ResponseEntity<ProblemDetail> conflict(HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Alias already exists", request);
    }

    @ExceptionHandler({StorageUnavailableException.class, GenerationExhaustedException.class})
    public ResponseEntity<ProblemDetail> unavailable(Exception error, HttpServletRequest request) {
        LOG.warn("Request unavailable type={}", error.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception error, HttpServletRequest request) {
        if (error instanceof ErrorResponse frameworkError
                && frameworkError.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = frameworkError.getStatusCode();
            HttpStatus standardStatus = HttpStatus.resolve(status.value());
            String detail =
                    standardStatus == null
                            ? "Client request rejected"
                            : standardStatus.getReasonPhrase();

            return problem(status, detail, request, frameworkError.getHeaders());
        }
        LOG.error("Unhandled request failure type={}", error.getClass().getSimpleName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request);
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatusCode status, String detail, HttpServletRequest request) {
        return problem(status, detail, request, HttpHeaders.EMPTY);
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatusCode status, String detail, HttpServletRequest request, HttpHeaders headers) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("requestId", request.getAttribute("requestId"));
        return ResponseEntity.status(status)
                .headers(headers)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
