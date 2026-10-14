package com.coffer.auth.service;

/** Blind object deletion for an already authorized, disabled account. Never returns file keys or bytes. */
public interface AccountObjectPurger {
    void purge(long targetUserId);
}
