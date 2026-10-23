package com.egorkurunov.shortlinks.dto;

import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateLinkRequest(@NotBlank @Size(max = TargetUrl.MAX_LENGTH) String url) {}
