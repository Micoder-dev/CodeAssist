package com.tyron.builder.api.internal.logging.events;


import com.tyron.builder.api.internal.logging.events.operations.LogEventLevel;
import com.tyron.builder.api.logging.LogLevel;

public class LogLevelConverter {
    public static LogEventLevel convert(LogLevel level) {
        switch (level) {
            case DEBUG:
                return LogEventLevel.DEBUG;
            case QUIET:
                return LogEventLevel.QUIET;
            case INFO:
                return LogEventLevel.INFO;
            case LIFECYCLE:
                return LogEventLevel.LIFECYCLE;
            case WARN:
                return LogEventLevel.WARN;
            case ERROR:
                return LogEventLevel.ERROR;
            default:
                throw new AssertionError();
        }
    }
}