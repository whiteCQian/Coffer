package com.coffer.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LoginRequest(@NotBlank @Size(max = 64) String username,
                           @NotNull @Size(min = 8, max = 16) String password) { }
