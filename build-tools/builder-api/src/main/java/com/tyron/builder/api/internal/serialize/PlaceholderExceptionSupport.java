package com.tyron.builder.api.internal.serialize;


import java.io.Serializable;

public interface PlaceholderExceptionSupport extends Serializable {

    StackTraceElement[] getStackTrace();

    String getExceptionClassName();

    String getMessage();
}