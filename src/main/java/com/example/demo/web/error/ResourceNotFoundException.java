package com.example.demo.web.error;

/**
 * 业务资源不存在。由统一异常处理器映射为 HTTP 404。
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
