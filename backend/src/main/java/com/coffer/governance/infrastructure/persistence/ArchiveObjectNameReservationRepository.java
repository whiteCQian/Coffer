package com.coffer.governance.infrastructure.persistence;

import com.coffer.governance.domain.ArchiveObjectNameReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface ArchiveObjectNameReservationRepository
        extends com.coffer.auth.infrastructure.OwnedRepository<ArchiveObjectNameReservation, Long> {

    @Query("select max(reservation.sequenceNumber) "
            + "from ArchiveObjectNameReservation reservation "
            + "where reservation.businessDate = :businessDate "
            + "and reservation.categorySlug = :categorySlug "
            + "and reservation.normalizedFileName = :normalizedFileName")
    Integer findMaxSequence(@Param("businessDate") LocalDate businessDate,
                            @Param("categorySlug") String categorySlug,
                            @Param("normalizedFileName") String normalizedFileName);
}
