package com.zodiac.api.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse("请求参数错误");
        return ResponseEntity.badRequest().body(Map.of(
                "error", "validation_failed",
                "message", msg
        ));
    }

    @ExceptionHandler(AdminAuthException.class)
    public ResponseEntity<?> adminAuth(AdminAuthException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", "admin_unauthorized",
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<?> payment(PaymentException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of(
                "error", e.getErrorCode(),
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> generic(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "internal_error",
                "message", "服务暂时不可用,请稍后再试"
        ));
    }
}
