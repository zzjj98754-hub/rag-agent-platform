package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class LoginRequest {

    @NotBlank(message = "username 不能为空")
    @Size(max = 64, message = "username 不能超过 64 个字符")
    private String username;

    @NotBlank(message = "password 不能为空")
    @Size(max = 128, message = "password 不能超过 128 个字符")
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
