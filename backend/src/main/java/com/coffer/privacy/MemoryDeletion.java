package com.coffer.privacy;

import jakarta.persistence.*;
import lombok.*;
import com.coffer.auth.domain.TenantOwnedEntity;

@Entity @Table(name = "memory_deletion") @Getter @Setter @NoArgsConstructor
public class MemoryDeletion extends TenantOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "session_id", nullable = false, length = 36) private String sessionId;
    public MemoryDeletion(String sessionId) { this.sessionId = sessionId; }
}
