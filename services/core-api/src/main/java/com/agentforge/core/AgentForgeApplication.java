package com.agentforge.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AgentForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentForgeApplication.class, args);
    }
}
