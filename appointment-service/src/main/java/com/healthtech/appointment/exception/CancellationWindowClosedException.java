package com.healthtech.appointment.exception;

public class CancellationWindowClosedException extends RuntimeException {
    public CancellationWindowClosedException(int minNoticeHours) {
        super("An appointment can only be cancelled up to " + minNoticeHours + " hours before it starts");
    }
}
