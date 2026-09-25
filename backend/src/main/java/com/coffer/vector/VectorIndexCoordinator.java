package com.coffer.vector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** Single-instance coordination point for all vector reads and writes. */
@Slf4j @Service @RequiredArgsConstructor
public class VectorIndexCoordinator {
    private final VectorIndexingService indexingService;
    private final RedisVectorStore store;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public boolean index(com.coffer.file.domain.FileMetadata metadata, String text) {
        lock.readLock().lock();
        try { return indexingService.indexParsedText(metadata, text); }
        finally { lock.readLock().unlock(); }
    }

    public boolean reindex(com.coffer.file.domain.FileMetadata metadata) {
        lock.readLock().lock();
        try { return indexingService.reindex(metadata); }
        finally { lock.readLock().unlock(); }
    }

    public void deleteFile(Long fileId) {
        lock.readLock().lock();
        try { store.deleteFile(fileId); }
        finally { lock.readLock().unlock(); }
    }

    public <T> T withRebuildLock(Supplier<T> operation) {
        lock.writeLock().lock();
        try { return operation.get(); }
        finally { lock.writeLock().unlock(); }
    }

    /** Search never waits longer than the business timeout for a rebuild writer. */
    public boolean tryRead(long timeoutMs) {
        try { return lock.readLock().tryLock(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    public void unlockRead() { lock.readLock().unlock(); }
}
