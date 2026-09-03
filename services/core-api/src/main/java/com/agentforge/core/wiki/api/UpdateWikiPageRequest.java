package com.agentforge.core.wiki.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateWikiPageRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull @Size(max = 100000) String content,
        @NotNull @PositiveOrZero Long version) {
}
