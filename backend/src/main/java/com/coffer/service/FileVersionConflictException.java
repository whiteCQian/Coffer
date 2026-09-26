package com.coffer.service;

@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
public class FileVersionConflictException extends RuntimeException {
    public FileVersionConflictException() { super("文件已变更，请重新检索"); }
}
