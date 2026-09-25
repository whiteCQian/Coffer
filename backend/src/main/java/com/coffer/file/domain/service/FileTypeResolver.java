package com.coffer.file.domain.service;

import org.springframework.stereotype.Component;

import java.util.Locale;

/** Resolves the normalized file extension used by metadata and object paths. */
@Component
public class FileTypeResolver {

    public String resolve(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.isBlank() ? null : extension;
    }
}
