package com.agentforge.core.agent.infrastructure;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AgentServiceProperties.class)
public class AgentServiceConfiguration {

    @Bean
    RestClient agentServiceRestClient(RestClient.Builder builder, AgentServiceProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.clone()
                .baseUrl(properties.baseUrl().toString())
                .defaultHeader("X-AgentForge-Internal-Token", properties.internalToken())
                .requestFactory(requestFactory)
                .build();
    }
}
