package com.campus.library.exception;

import org.springframework.http.HttpStatus;

/**
 * 统一业务异常（对应《详细设计文档》2.4 节公共约定）。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final String requestId;

    public ApiException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, null);
    }

    public ApiException(HttpStatus status, String errorCode, String message, String requestId) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.requestId = requestId;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getRequestId() {
        return requestId;
    }
}
