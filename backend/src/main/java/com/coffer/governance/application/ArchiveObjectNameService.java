package com.coffer.governance.application;

import com.coffer.file.domain.CategoryType;
import com.coffer.file.infrastructure.storage.PathGenerator;
import com.coffer.governance.domain.ArchiveObjectNameReservation;
import com.coffer.governance.infrastructure.persistence.ArchiveObjectNameAllocatorLockRepository;
import com.coffer.governance.infrastructure.persistence.ArchiveObjectNameReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 为归档对象分配“同一天、同名文件”的三位十六进制序号。
 */
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class ArchiveObjectNameService {

    private static final long ALLOCATOR_LOCK_ID = 1L;
    private static final int MAX_SEQUENCE_NUMBER = 0xFFF;

    private final ArchiveObjectNameAllocatorLockRepository allocatorLockRepository;
    private final ArchiveObjectNameReservationRepository reservationRepository;
    private final PathGenerator pathGenerator;

    /**
     * 序号在独立事务中落库，预览事务回滚时不会把已经分配过的序号重新释放给并发请求。
     * 因此失败或取消的预览可能产生空号，但不会发生对象名冲突。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String generateArchivePath(CategoryType categoryType,
                                      String originalFilename,
                                      LocalDateTime uploadTime) {
        LocalDateTime effectiveTime = uploadTime == null ? LocalDateTime.now() : uploadTime;
        LocalDate businessDate = effectiveTime.toLocalDate();
        String categorySlug = categoryType == null ? CategoryType.OTHER.getSlug() : categoryType.getSlug();
        String normalizedFileName = normalizeFileName(originalFilename);

        allocatorLockRepository.findByIdForUpdate(ALLOCATOR_LOCK_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "archive object name allocator lock row is not initialized"));

        Integer maxSequence = reservationRepository.findMaxSequence(
                businessDate, categorySlug, normalizedFileName);
        int nextSequence = maxSequence == null ? 0 : maxSequence + 1;
        if (nextSequence > MAX_SEQUENCE_NUMBER) {
            throw new IllegalStateException("archive object name sequence exhausted for "
                    + businessDate + " and file " + originalFilename);
        }

        reservationRepository.saveAndFlush(ArchiveObjectNameReservation.builder()
                .businessDate(businessDate)
                .categorySlug(categorySlug)
                .normalizedFileName(normalizedFileName)
                .sequenceNumber(nextSequence)
                .build());

        return pathGenerator.generateArchivePath(categoryType, originalFilename, effectiveTime, nextSequence);
    }

    private String normalizeFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "unnamed-file";
        }
        return Normalizer.normalize(originalFilename.trim(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
    }
}
