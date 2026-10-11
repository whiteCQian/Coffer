package com.coffer.auth.api.dto;

public record AuthStatusResponse(boolean setupRequired, boolean setupAvailable, int passwordMinLength, boolean strongPasswordRequired, boolean desktopPersonal, boolean storageQuotaEnabled) {
    public AuthStatusResponse(boolean setupRequired, boolean setupAvailable, int passwordMinLength, boolean strongPasswordRequired, boolean desktopPersonal) {
        this(setupRequired, setupAvailable, passwordMinLength, strongPasswordRequired, desktopPersonal, false);
    }
    public AuthStatusResponse(boolean setupRequired, boolean setupAvailable, int passwordMinLength, boolean strongPasswordRequired) {
        this(setupRequired, setupAvailable, passwordMinLength, strongPasswordRequired, false);
    }
}
