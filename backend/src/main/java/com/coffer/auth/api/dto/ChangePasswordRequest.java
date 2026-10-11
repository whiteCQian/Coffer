package com.coffer.auth.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(@NotNull @Size(max = 128) String currentPassword,
                                    @NotNull @Size(min = 8, max = 16) String newPassword) { }
