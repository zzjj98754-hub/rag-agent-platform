package com.example.demo.web.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * MVC 统一异常映射。业务层抛异常，Controller 不捕获也不拼装错误 Map。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ApiErrorFactory errorFactory;

    public GlobalExceptionHandler(ApiErrorFactory errorFactory) {
        this.errorFactory = errorFactory;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> invalidBody(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "请求参数校验失败",
                request,
                details);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiErrorResponse> bindFailure(
            BindException exception,
            HttpServletRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return response(
                HttpStatus.BAD_REQUEST,
                "BINDING_FAILED",
                "请求参数绑定失败",
                request,
                details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> constraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation ->
                details.put(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()));
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "请求参数校验失败",
                request,
                details);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiErrorResponse> malformedRequest(
            Exception exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "请求格式或参数类型不正确",
                request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> authenticationFailure(
            AuthenticationException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_FAILED",
                "用户名、密码或 Token 无效",
                request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> badCredentials(
            BadCredentialsException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_FAILED",
                "用户名或密码错误",
                request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> accessDenied(
            AccessDeniedException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "没有权限访问该资源",
                request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> notFound(
            NoResourceFoundException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "请求资源不存在",
                request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> businessResourceNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> invalidArgument(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_ARGUMENT",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unexpected(
            Exception exception,
            HttpServletRequest request) {
        log.error(
                "未处理异常 | method={} path={}",
                request.getMethod(),
                request.getRequestURI(),
                exception);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "系统暂时不可用，请稍后重试",
                request);
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request) {
        return response(status, code, message, request, Map.of());
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, String> details) {
        return ResponseEntity.status(status).body(errorFactory.create(
                status,
                code,
                message,
                request.getRequestURI(),
                details));
    }
}
