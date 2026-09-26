package com.coffer.task.application;

import com.coffer.task.domain.AsyncTask;
import com.coffer.task.domain.AsyncTaskStatus;
import com.coffer.task.infrastructure.persistence.AsyncTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskProgressApplicationServiceTest {

    private AsyncTaskRepository repository;
    private TaskProgressApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(AsyncTaskRepository.class);
        service = new TaskProgressApplicationService(repository);
    }

    @Test
    void mapsTaskToProgressResponse() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 13, 16, 0);
        LocalDateTime updatedAt = createdAt.plusMinutes(1);
        AsyncTask task = AsyncTask.builder()
                .taskId("task-1")
                .fileName("报告.pdf")
                .status(AsyncTaskStatus.PROCESSING)
                .progress(50)
                .result("处理中")
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
        when(repository.findByTaskId("task-1")).thenReturn(Optional.of(task));

        var response = service.getTaskProgress("task-1");

        assertThat(response.getTaskId()).isEqualTo("task-1");
        assertThat(response.getFileName()).isEqualTo("报告.pdf");
        assertThat(response.getStatus()).isEqualTo("PROCESSING");
        assertThat(response.getProgress()).isEqualTo(50);
        assertThat(response.getResult()).isEqualTo("处理中");
        assertThat(response.getCreatedAt()).isEqualTo(createdAt);
        assertThat(response.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void missingTaskThrowsBusinessException() {
        when(repository.findByTaskId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTaskProgress("missing"))
                .isInstanceOf(com.coffer.auth.service.ResourceNotFoundException.class)
                .hasMessage("资源不存在");
    }

    @Test
    void nullStatusRemainsNull() {
        AsyncTask task = AsyncTask.builder().taskId("task-null-status").status(null).build();
        when(repository.findByTaskId("task-null-status")).thenReturn(Optional.of(task));

        assertThat(service.getTaskProgress("task-null-status").getStatus()).isNull();
    }
}
