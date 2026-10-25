package io.kubespawn.platform.lifecycle;

import org.springframework.http.HttpStatus;

public class LifecycleException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public LifecycleException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
