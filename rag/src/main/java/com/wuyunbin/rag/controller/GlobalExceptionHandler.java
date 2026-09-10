package com.wuyunbin.rag.controller;

import java.io.IOException;
import java.time.LocalDateTime;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一异常处理：把校验/格式/上游等异常转成固定结构的错误响应。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    record ErrorBody(int code, String message, String path, String timestamp) {
        static ErrorBody of(int code, String message) {
            return new ErrorBody(code, message, null, LocalDateTime.now().toString());
        }

        static ErrorBody of(int code, String message, String path) {
            return new ErrorBody(code, message, path, LocalDateTime.now().toString());
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(ErrorBody.of(400, msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ErrorBody.of(400, "请求体格式错误"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorBody> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ErrorBody.of(415, "Content-Type 必须为 application/json"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorBody.of(404, "资源不存在"));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ErrorBody> handleIo(IOException ex, HttpServletRequest request) {
        log.error("IO 处理失败 (path={})", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorBody.of(400, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("未捕获异常 (path={})", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorBody.of(500, "服务处理失败", request.getRequestURI()));
    }
}