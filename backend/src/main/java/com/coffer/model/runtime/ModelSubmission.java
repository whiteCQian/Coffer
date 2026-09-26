package com.coffer.model.runtime;

import java.lang.annotation.*;
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface ModelSubmission { String value(); }
