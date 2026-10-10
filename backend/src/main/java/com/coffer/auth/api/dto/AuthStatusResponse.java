package com.coffer.auth.api.dto;

public record AuthStatusResponse(boolean setupRequired, boolean setupAvailable, int passwordMinLength, boolean strongPasswordRequired) { }
