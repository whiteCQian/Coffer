package com.coffer.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InitialAdminRequest(
        @NotBlank @Size(max = 256) String setupToken,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,64}") String username,
        @NotBlank @Size(min = 12, max = 72) String password) { }
