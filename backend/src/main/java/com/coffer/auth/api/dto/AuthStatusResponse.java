package com.coffer.auth.api.dto;

public record AuthStatusResponse(boolean setupRequired, boolean setupAvailable) { }
