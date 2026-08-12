package com.example.demo.persistence.mapper;

import com.example.demo.persistence.entity.UserEntity;
import org.apache.ibatis.annotations.Param;

public interface UserMapper {

    int insert(UserEntity user);

    UserEntity findById(@Param("id") Long id);

    UserEntity findByUsername(@Param("username") String username);

    int updatePassword(@Param("id") Long id, @Param("password") String password);

    int updateRole(@Param("id") Long id, @Param("role") String role);
}
