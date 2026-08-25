package com.example.demo.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillPromptRendererTest {
    private final SkillPromptRenderer renderer = new SkillPromptRenderer(1000);

    @Test
    void shouldRenderRequiredVariables() {
        String result = renderer.render(
                "为 {{team}} 生成 {{period}} 周报",
                Map.of("team", "平台组", "period", "本周"),
                Map.of("required", List.of("team", "period")));
        assertThat(result).isEqualTo("为 平台组 生成 本周 周报");
    }

    @Test
    void shouldRejectMissingVariables() {
        assertThatThrownBy(() -> renderer.render(
                "Hello {{name}}", Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }
}
