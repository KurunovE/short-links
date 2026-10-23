package com.egorkurunov.shortlinks.dto;

import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateLinkRequest(
        @NotBlank @Size(max = TargetUrl.MAX_LENGTH) String url,
        @Size(min = ShortCode.MIN_LENGTH, max = ShortCode.MAX_LENGTH)
                @Pattern(regexp = "[a-zA-Z0-9_-]+")
                String alias) {}
