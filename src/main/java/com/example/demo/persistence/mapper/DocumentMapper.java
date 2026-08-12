package com.example.demo.persistence.mapper;

import com.example.demo.persistence.entity.DocumentEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface DocumentMapper {

    int upsert(DocumentEntity document);

    DocumentEntity findById(@Param("id") Long id);

    DocumentEntity findByFilePath(@Param("filePath") String filePath);

    List<DocumentEntity> findByStatus(@Param("status") String status);

    List<DocumentEntity> findAll();

    int updateStatusByFilePath(
            @Param("filePath") String filePath,
            @Param("status") String status);

    int deleteById(@Param("id") Long id);
}
