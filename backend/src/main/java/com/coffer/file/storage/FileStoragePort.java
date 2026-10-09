package com.coffer.file.storage;

import java.io.InputStream;
import java.util.List;

/** The owner-scoped object store used by file workflows on every deployment. */
public interface FileStoragePort {
    /** Local filesystem identity; remote stores keep their existing object identity checks. */
    record LocalIdentity(long size, String modifiedTime, String fileKey) { }
    default LocalIdentity localIdentity(String key) { return null; }
    record StoredObject(String key, long size, String sha256, String etag, String versionId) {
        public StoredObject(String key, long size, String sha256, String etag) {
            this(key, size, sha256, etag, null);
        }
    }

    /** Publish a new object. An existing destination is always a conflict. */
    StoredObject write(String key, InputStream source, String contentType, long size);

    /** Publish exactly the source confirmed in an import preview; local adapters check before publication. */
    default StoredObject writeVerified(String key, InputStream source, String contentType, long size, String sha256) {
        StoredObject object = write(key, source, contentType, size);
        if (!java.util.Objects.equals(sha256, object.sha256()) || size != object.size())
            throw new StorageConflictException("导入来源与确认的正文不一致");
        return object;
    }

    InputStream read(String key);

    InputStream readRange(String key, long offset, long length);

    /** Open exactly the object observed by stat, or fail before exposing bytes. */
    InputStream readIfUnchanged(String key, StoredObject expected);

    /** Open a range from exactly the object observed by stat. */
    InputStream readRangeIfUnchanged(String key, StoredObject expected, long offset, long length);

    StoredObject stat(String key);

    boolean exists(String key);

    /** The expected digest must match the source, and the destination must be absent. */
    StoredObject copy(String source, String target, String expectedSourceSha256);

    /** The expected digest must match the source, and the destination must be absent. */
    StoredObject move(String source, String target, String expectedSourceSha256);

    /** Delete only the object whose digest still matches the expected value. */
    void delete(String key, String expectedSha256);

    default String sha256(String key) { return stat(key).sha256(); }

    /** Inventory the current owner's published objects for crash reconciliation. */
    default List<String> listOwnedKeys() { return List.of(); }

    /** Historical versions and delete markers that need durable manual review. */
    record VersionIssue(String key, String code) { }

    default List<VersionIssue> listOwnedVersionIssues() { return List.of(); }
}
