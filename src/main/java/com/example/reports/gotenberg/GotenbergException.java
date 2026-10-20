package com.example.reports.gotenberg;

/**
 * A failed Gotenberg call. {@code status} is the HTTP status Gotenberg answered with, or 0 when
 * no response arrived (connection refused, timeout).
 */
public class GotenbergException extends RuntimeException {

    private final int status;

    public GotenbergException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /**
     * 4xx means the request itself is wrong (bad HTML form, missing index.html) and sending it again
     * will not help. 429, 5xx and transport errors are worth another attempt: Gotenberg answers 503
     * when its own timeout fires or Chromium is restarting.
     */
    public boolean isRetryable() {
        return status == 0 || status == 429 || status >= 500;
    }
}
