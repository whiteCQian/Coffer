package com.coffer.inbox;

import com.coffer.auth.service.TenantContext;
import com.coffer.config.InboxImportProperties;
import com.coffer.inbox.application.*;
import com.coffer.inbox.domain.*;
import com.coffer.inbox.infrastructure.persistence.InboxImportRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InboxImportProgressTest {
    @Test void oldPendingPreviewStaysVisibleAheadOfNewlyCompletedRows() {
        TenantContext.set(7L);
        try {
            var properties = new InboxImportProperties(); properties.setEnabled(true); properties.setDirectory("C:/{ownerId}");
            var records = mock(InboxImportRecordRepository.class);
            var directories = mock(InboxDirectoryResolver.class); when(directories.requiresConfirmation()).thenReturn(true);
            var pending = InboxImportRecord.builder().id(1L).sourceFileName("older.txt")
                    .targetPath("users/7/managed/files/older.txt").status(InboxImportStatus.AWAITING_CONFIRMATION).build();
            var completed = java.util.stream.LongStream.rangeClosed(2, 51).mapToObj(id -> InboxImportRecord.builder()
                    .id(id).sourceFileName("completed-" + id + ".txt").status(InboxImportStatus.IMPORTED).build()).toList();
            when(records.findTop50ByStatusOrderByUpdatedAtDesc(InboxImportStatus.AWAITING_CONFIRMATION)).thenReturn(List.of(pending));
            when(records.findTop50ByOrderByUpdatedAtDesc()).thenReturn(completed);
            var scanner = new InboxImportScanner(properties, records, null, null);
            ReflectionTestUtils.setField(scanner, "directories", directories);
            var items = scanner.getProgress().getItems();
            assertThat(items).hasSize(50);
            assertThat(items.get(0).getId()).isEqualTo(1L);
            assertThat(items.get(0).getTargetPath()).isEqualTo(pending.getTargetPath());
        } finally { TenantContext.clear(); }
    }
}
