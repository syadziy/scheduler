package com.mac.scheduler.utils.exception;

public class SchedulerConflictException extends RuntimeException {

    public SchedulerConflictException(String message) {
        super(message);
    }

    public SchedulerConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
