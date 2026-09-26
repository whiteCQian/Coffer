package com.coffer.memory;

/** Never accept a caller-provided Redis key or a model-provided owner. */
public record OwnerMemoryId(Long ownerId, String sessionId) {}
