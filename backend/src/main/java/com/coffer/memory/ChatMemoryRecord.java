package com.coffer.memory;

import com.coffer.auth.domain.TenantOwnedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_memory", uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "session_id"}))
@Getter @Setter @NoArgsConstructor
public class ChatMemoryRecord extends TenantOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "session_id", nullable = false, length = 36) private String sessionId;
    @Column(name = "envelope", nullable = false, columnDefinition = "TEXT") private String envelope;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    public ChatMemoryRecord(String sessionId) { this.sessionId = sessionId; }
}
