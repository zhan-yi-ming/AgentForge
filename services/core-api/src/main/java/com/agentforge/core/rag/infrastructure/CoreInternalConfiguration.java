package com.agentforge.core.rag.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CoreInternalProperties.class)
public class CoreInternalConfiguration {
}
