package com.coffer.auth.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(@NotNull @Size(min = 8, max = 16) String newPassword) { }
