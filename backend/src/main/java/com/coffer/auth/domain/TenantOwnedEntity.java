package com.coffer.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

/** Shared owner discriminator for every user-owned row. */
@MappedSuperclass
public abstract class TenantOwnedEntity {

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    public Long getOwnerId() {
        return ownerId;
    }
}
