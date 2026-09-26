package com.coffer.auth.service;

/** Deliberately identical for absent and inaccessible private resources. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException() { super("资源不存在"); }
}
