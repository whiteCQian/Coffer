package com.coffer.auth.api.dto;

public record AuthStatusResponse(boolean setupRequired, boolean setupAvailable, int passwordMinLength, boolean strongPasswordRequired, boolean desktopPersonal) {
    public AuthStatusResponse(boolean setupRequired, boolean setupAvailable, int passwordMinLength, boolean strongPasswordRequired) {
        this(setupRequired, setupAvailable, passwordMinLength, strongPasswordRequired, false);
    }
}
