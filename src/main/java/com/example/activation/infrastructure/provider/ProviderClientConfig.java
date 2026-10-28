package com.example.activation.infrastructure.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
class ProviderClientConfig {

    @Bean
    RestClient providerRestClient(RestClient.Builder builder, ProviderProperties properties) {
        return createRestClient(builder, properties);
    }

    static RestClient createRestClient(RestClient.Builder builder, ProviderProperties properties) {
        // HTTP/1.1 explícito: o padrão do JDK tenta upgrade h2c em http://, que nem todo servidor aceita.
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
