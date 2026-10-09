package com.coffer.privacy;

/** Erases the current owner's memory after the session has already been deleted. */
public interface MemoryCleanup {
    void delete(String sessionId);
}
