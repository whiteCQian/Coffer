package com.coffer.task.infrastructure.persistence;

import com.coffer.task.domain.AsyncTask;
import com.coffer.task.domain.AsyncTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;

/**
 * 异步任务 Repository。
 */
@Repository
public interface AsyncTaskRepository extends com.coffer.auth.infrastructure.OwnedRepository<AsyncTask, Long> {

    /**
     * 按任务 ID 查询任务详情。
     *
     * @param taskId 任务 ID
     * @return 任务（可能为空）
     */
    Optional<AsyncTask> findByTaskId(String taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AsyncTask t where t.taskId = :taskId")
    Optional<AsyncTask> lockByTaskId(String taskId);

    /**
     * 按状态筛选任务，按创建时间倒序排列。
     *
     * @param status 任务状态
     * @return 任务列表
     */
    List<AsyncTask> findByStatusOrderByCreatedAtDesc(AsyncTaskStatus status);

    List<AsyncTask> findByStatusOrderByCreatedAtAsc(AsyncTaskStatus status, Pageable limit);

    @Query("select t from AsyncTask t where t.status = :status "
            + "and (t.leaseUntil is null or t.leaseUntil <= :now) order by t.createdAt asc")
    List<AsyncTask> findExpired(@Param("status") AsyncTaskStatus status,
                                @Param("now") LocalDateTime now, Pageable limit);
}
