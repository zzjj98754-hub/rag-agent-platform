package com.example.demo.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class MyBatisMapperXmlTest {

    @Test
    void mapperXmlFilesShouldParseAndRegisterStatements() {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry()
                .registerAliases("com.example.demo.persistence.entity");

        List<String> resources = List.of(
                "mapper/UserMapper.xml",
                "mapper/DocumentMapper.xml",
                "mapper/ChatSessionMapper.xml",
                "mapper/ChatMessageMapper.xml");

        for (String resource : resources) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertThat(input)
                        .as("Mapper resource %s should exist", resource)
                        .isNotNull();
                new XMLMapperBuilder(
                        input,
                        configuration,
                        resource,
                        configuration.getSqlFragments())
                        .parse();
            } catch (Exception e) {
                throw new AssertionError("Failed to parse " + resource, e);
            }
        }

        assertThat(configuration.hasStatement(
                "com.example.demo.persistence.mapper.UserMapper.insert")).isTrue();
        assertThat(configuration.hasStatement(
                "com.example.demo.persistence.mapper.DocumentMapper.upsert")).isTrue();
        assertThat(configuration.hasStatement(
                "com.example.demo.persistence.mapper.ChatSessionMapper.findBySessionId")).isTrue();
        assertThat(configuration.hasStatement(
                "com.example.demo.persistence.mapper.ChatMessageMapper.findRecentBySessionId")).isTrue();
    }
}
