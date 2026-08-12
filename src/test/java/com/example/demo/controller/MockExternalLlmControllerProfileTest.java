package com.example.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;

class MockExternalLlmControllerProfileTest {

    @Test
    void mockControllerIsRestrictedToNonProductionProfiles() {
        Profile profile =
                MockExternalLlmController.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder(
                "dev",
                "loadtest");
    }

    @Test
    void productionProfileDoesNotRegisterMockController() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("prod");
            context.registerBean(ObjectMapper.class);
            context.register(MockExternalLlmController.class);
            context.refresh();

            assertThat(context.getBeansOfType(
                            MockExternalLlmController.class))
                    .isEmpty();
        }
    }
}
