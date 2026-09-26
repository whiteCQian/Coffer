package com.coffer.auth.service;

/** Server-created wake-up envelope; the worker must reload the persisted intent in this owner scope. */
public interface OwnedWork { Long ownerId(); }
