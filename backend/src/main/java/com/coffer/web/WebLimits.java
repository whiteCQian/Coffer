package com.coffer.web;

import com.coffer.auth.service.TenantContext;
import com.coffer.file.storage.StorageKey;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** SQL reservations survive restart and charge orphan/retained objects until verified physical deletion.
 * One global row serializes quota decisions across requests/processes. No transaction holds a lock over object IO. */
@Service @Profile("prod") @RequiredArgsConstructor @com.coffer.auth.service.OwnerOnly
public class WebLimits {
    private final JdbcTemplate jdbc;
    private final WebLimitProperties properties;
    private final MinioVolumeCapacity capacity;
    public record Usage(long usedBytes, long objectCount, long limitBytes, int limitObjects, int pendingTaskLimit, int concurrentTaskLimit) { }
    private void lock() { jdbc.queryForObject("SELECT id FROM web_quota_lock WHERE id=1 FOR UPDATE", Long.class); }
    private void ready() {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT initialized FROM web_quota_lock WHERE id=1", Boolean.class)))
            throw new WebLimitException(503, "存储配额清单正在核对，请稍后重试或联系管理员");
    }
    public static String hash(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserve(String key, long size) {
        StorageKey.requireOwned(key);
        if (size < 0 || size > 32L * 1024 * 1024) throw new WebLimitException(413, "单文件大小超过限制（最大 32MB）");
        long owner = TenantContext.requireOwnerId(); lock(); ready();
        String digest = hash(key);
        var previous = jdbc.queryForList("SELECT bytes,owner_id,object_key,state FROM web_storage_allocation WHERE key_hash=?", digest);
        if (!previous.isEmpty()) {
            var row = previous.get(0);
            if ("STORED".equals(row.get("state"))) throw new WebLimitException(409, "目标对象已有容量台账，禁止覆盖；请先核对文件操作记录");
            if (((Number)row.get("owner_id")).longValue() != owner || !key.equals(row.get("object_key")) || ((Number)row.get("bytes")).longValue() != size)
                throw new WebLimitException(409, "已有对象预约与本次写入不一致，请先核对操作台账");
            // Retried writes still need live capacity evidence, but never charge a second reservation.
            checkCapacity(0); return;
        }
        long used = sum("SELECT COALESCE(SUM(bytes),0) FROM web_storage_allocation WHERE owner_id=?", owner);
        long count = sum("SELECT COUNT(*) FROM web_storage_allocation WHERE owner_id=?", owner);
        if (size > properties.getAccountBytes() - used || count >= properties.getAccountObjects())
            throw new WebLimitException(413, "账号存储配额已达到上限；请清理文件并等待物理清理完成，或联系管理员调整配额");
        long total = sum("SELECT COALESCE(SUM(bytes),0) FROM web_storage_allocation");
        if (size > properties.getTotalBytes() - total) throw new WebLimitException(507, "服务存储配额已达到上限，请联系管理员扩容");
        checkCapacity(size);
        jdbc.update("INSERT INTO web_storage_allocation(key_hash,owner_id,object_key,bytes,state,created_at) VALUES(?,?,?,?,'RESERVED',?)", digest, owner, key, size, Timestamp.from(Instant.now()));
    }
    private void checkCapacity(long additional) {
        var sample = capacity.sample();
        long recent = sum("SELECT COALESCE(SUM(bytes),0) FROM web_storage_allocation WHERE state='RESERVED' OR created_at>=?", Timestamp.from(sample.checkedAt()));
        long floor = Math.max(properties.getReserveFreeBytes(), (long)Math.ceil(sample.totalBytes() * (properties.getReserveFreePercent() / 100.0)));
        if (recent > sample.freeBytes() - floor || additional > sample.freeBytes() - floor - recent)
            throw new WebLimitException(507, "MinIO 持久卷剩余容量不足；已停止新增写入，请联系管理员清理或扩容");
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stored(String key) {
        StorageKey.requireOwned(key); lock();
        jdbc.update("UPDATE web_storage_allocation SET state='STORED' WHERE key_hash=? AND owner_id=?", hash(key), TenantContext.requireOwnerId());
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleted(String key) {
        StorageKey.requireOwned(key); lock();
        jdbc.update("DELETE FROM web_storage_allocation WHERE key_hash=? AND owner_id=?", hash(key), TenantContext.requireOwnerId());
    }
    // Called within durable task/intent registration transactions; the lock lasts until their commit.
    @Transactional(propagation = Propagation.MANDATORY)
    public void admitTask(String taskId) {
        lock();
        long owner = TenantContext.requireOwnerId();
        long pending = sum("SELECT COUNT(*) FROM async_task WHERE owner_id=? AND status IN ('PENDING','PROCESSING') AND task_id<>?", owner, taskId);
        pending += sum("SELECT COUNT(*) FROM file_write_intent i WHERE i.owner_id=? AND i.task_id<>? AND i.status IN ('PREPARED','OBJECT_WRITTEN') AND NOT EXISTS(SELECT 1 FROM async_task t WHERE t.task_id=i.task_id AND t.owner_id=i.owner_id)", owner, taskId);
        if (pending >= properties.getPendingTasks()) throw new WebLimitException(429, "账号待处理任务已达到上限，请等待任务完成后重试");
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean canClaimTask() {
        lock();
        return sum("SELECT COUNT(*) FROM async_task WHERE owner_id=? AND status='PROCESSING'", TenantContext.requireOwnerId()) < properties.getConcurrentTasks();
    }
    @Transactional(readOnly = true)
    public Usage usage() {
        long owner = TenantContext.requireOwnerId();
        return new Usage(sum("SELECT COALESCE(SUM(bytes),0) FROM web_storage_allocation WHERE owner_id=?", owner),
                sum("SELECT COUNT(*) FROM web_storage_allocation WHERE owner_id=?", owner), properties.getAccountBytes(), properties.getAccountObjects(), properties.getPendingTasks(), properties.getConcurrentTasks());
    }
    private long sum(String query, Object... args) { return jdbc.queryForObject(query, Long.class, args); }
}
