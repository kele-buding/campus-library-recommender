package com.campus.library.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理：统一返回 error_code、message、request_id（详设 2.4 节）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error_code", ex.getErrorCode());
        body.put("message", ex.getMessage());
        body.put("request_id", ex.getRequestId());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        FieldError first = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String msg = first != null ? first.getField() + ": " + first.getDefaultMessage() : "请求参数校验失败";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error_code", "INVALID_ARGUMENT");
        body.put("message", msg);
        body.put("request_id", null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error_code", "INVALID_PAYLOAD");
        body.put("message", "请求体格式不正确");
        body.put("request_id", null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> handleParamMismatch(Exception ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error_code", "INVALID_ARGUMENT");
        body.put("message", "请求参数缺失或类型不正确");
        body.put("request_id", null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
