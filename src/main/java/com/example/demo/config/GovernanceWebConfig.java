package com.example.demo.config;

import com.example.demo.governance.AuditLogFilter;
import com.example.demo.governance.IdempotencyInterceptor;
import com.example.demo.governance.QuotaInterceptor;
import com.example.demo.governance.RateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class GovernanceWebConfig implements WebMvcConfigurer {
    private final QuotaInterceptor quota;
    public GovernanceWebConfig(QuotaInterceptor quota) { this.quota = quota; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(quota)
                .addPathPatterns("/chat/**", "/agent/**", "/workflows/**");
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> disableRateLimitAutoRegistration(
            RateLimitFilter filter) {
        return disabled(filter);
    }

    @Bean
    public FilterRegistrationBean<IdempotencyInterceptor> disableIdempotencyAutoRegistration(
            IdempotencyInterceptor filter) {
        return disabled(filter);
    }

    @Bean
    public FilterRegistrationBean<AuditLogFilter> disableAuditAutoRegistration(
            AuditLogFilter filter) {
        return disabled(filter);
    }

    private <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> disabled(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
