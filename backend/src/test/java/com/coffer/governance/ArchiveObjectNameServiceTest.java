package com.coffer.governance;

import com.coffer.file.domain.CategoryType;
import com.coffer.governance.application.ArchiveObjectNameService;
import com.coffer.governance.infrastructure.persistence.ArchiveObjectNameReservationRepository;
import com.coffer.service.MinioStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:archive_object_name_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.locations=classpath:db/migration/h2",
        "minio.access-key=test-access-key",
        "minio.secret-key=test-secret-key"
})
class ArchiveObjectNameServiceTest {

    @Autowired
    private ArchiveObjectNameService archiveObjectNameService;

    @Autowired
    private ArchiveObjectNameReservationRepository reservationRepository;

    @MockitoBean
    private MinioStorageService minioStorageService;

    @BeforeEach
    void cleanReservations() {
        reservationRepository.deleteAll();
    }

    @Test
    void allocatesSequenceForSameDayAndSameName() {
        LocalDateTime uploadTime = LocalDateTime.of(2026, 9, 21, 10, 30);

        String first = archiveObjectNameService.generateArchivePath(
                CategoryType.CONTRACT, "采购合同.TXT", uploadTime);
        String second = archiveObjectNameService.generateArchivePath(
                CategoryType.CONTRACT, "采购合同.txt", uploadTime);

        assertThat(first).isEqualTo("contracts/2026/09/21/000-采购合同.txt");
        assertThat(second).isEqualTo("contracts/2026/09/21/001-采购合同.txt");
    }

    @Test
    void resetsSequenceForAnotherDateOrAnotherName() {
        String anotherDate = archiveObjectNameService.generateArchivePath(
                CategoryType.CONTRACT, "采购合同.txt", LocalDateTime.of(2026, 9, 22, 10, 30));
        String anotherName = archiveObjectNameService.generateArchivePath(
                CategoryType.CONTRACT, "销售合同.txt", LocalDateTime.of(2026, 9, 21, 10, 30));

        assertThat(anotherDate).isEqualTo("contracts/2026/09/22/000-采购合同.txt");
        assertThat(anotherName).isEqualTo("contracts/2026/09/21/000-销售合同.txt");
    }

    @Test
    void resetsSequenceForAnotherCategoryFolder() {
        LocalDateTime uploadTime = LocalDateTime.of(2026, 9, 21, 10, 30);

        String contractPath = archiveObjectNameService.generateArchivePath(
                CategoryType.CONTRACT, "采购合同.txt", uploadTime);
        String invoicePath = archiveObjectNameService.generateArchivePath(
                CategoryType.INVOICE, "采购合同.txt", uploadTime);

        assertThat(contractPath).isEqualTo("contracts/2026/09/21/000-采购合同.txt");
        assertThat(invoicePath).isEqualTo("invoices/2026/09/21/000-采购合同.txt");
    }
}
