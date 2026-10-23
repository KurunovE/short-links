package com.egorkurunov.shortlinks.adapter.in.web;

import com.egorkurunov.shortlinks.application.port.in.CreateLink;
import com.egorkurunov.shortlinks.application.port.in.ResolveLink;
import com.egorkurunov.shortlinks.application.port.in.UpdateLink;
import com.egorkurunov.shortlinks.domain.model.Link;
import com.egorkurunov.shortlinks.dto.CreateLinkRequest;
import com.egorkurunov.shortlinks.dto.LinkResponse;
import com.egorkurunov.shortlinks.dto.UpdateLinkRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ApiResponses({
    @ApiResponse(
            responseCode = "400",
            description = "Invalid URL, code, or request body",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
            responseCode = "503",
            description = "Storage unavailable or generated-code attempts exhausted",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
            responseCode = "500",
            description = "Unexpected server error",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
})
@RequiredArgsConstructor
public final class LinkController {

    private final CreateLink create;
    private final UpdateLink update;
    private final ResolveLink resolve;
    private final PublicBaseUrl publicBaseUrl;
    private final MeterRegistry metrics;

    @Operation(summary = "Create a short link")
    @ApiResponse(
            responseCode = "201",
            description = "Created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LinkResponse.class)),
            headers =
                    @Header(
                            name = "Location",
                            description = "Public short URL",
                            schema = @Schema(type = "string")))
    @ApiResponse(
            responseCode = "409",
            description = "Alias already exists",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/api/links")
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
        LinkResponse response = response(create.create(request.url(), request.alias()));
        return ResponseEntity.created(URI.create(response.shortUrl())).body(response);
    }

    @Operation(summary = "Change a link target")
    @ApiResponse(
            responseCode = "200",
            description = "Target updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LinkResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Link not found",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping("/api/links/{code}")
    public LinkResponse update(
            @PathVariable String code, @Valid @RequestBody UpdateLinkRequest request) {
        return response(update.update(code, request.url()));
    }

    @Operation(summary = "Redirect to the current target")
    @ApiResponse(
            responseCode = "302",
            description = "Temporary redirect; never cached by the client",
            content = @Content,
            headers = {
                @Header(
                        name = "Location",
                        description = "Current target URL",
                        schema = @Schema(type = "string")),
                @Header(
                        name = "Cache-Control",
                        schema = @Schema(type = "string", allowableValues = "no-store"))
            })
    @ApiResponse(
            responseCode = "404",
            description = "Link not found",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String url = resolve.resolve(code).value();
        metrics.counter("shortlinks.redirects").increment();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(url))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private LinkResponse response(Link link) {
        return new LinkResponse(
                link.code().value(),
                link.url().value(),
                publicBaseUrl.value() + "/" + link.code().value());
    }
}
