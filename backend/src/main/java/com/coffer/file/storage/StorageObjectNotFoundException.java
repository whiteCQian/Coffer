package com.coffer.file.storage;

public class StorageObjectNotFoundException extends RuntimeException {
    public StorageObjectNotFoundException() { super("存储对象不存在"); }
    public StorageObjectNotFoundException(String message) { super(message); }
}
