package com.example.demo.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class StreamingWebClientConfig {

    @Bean("streamingLlmWebClient")
    public WebClient streamingLlmWebClient(
            WebClient.Builder builder,
            @Value("${app.llm.connect-timeout}") long connectTimeoutSeconds,
            @Value("${app.llm.read-timeout}") long readTimeoutSeconds) {
        int connectTimeoutMillis = Math.toIntExact(
                Duration.ofSeconds(connectTimeoutSeconds).toMillis());
        HttpClient httpClient = HttpClient.create()
                .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        connectTimeoutMillis)
                .responseTimeout(Duration.ofSeconds(readTimeoutSeconds));
        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
