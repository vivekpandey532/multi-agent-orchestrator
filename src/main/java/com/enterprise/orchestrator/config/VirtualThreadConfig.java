package com.enterprise.orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Provides a virtual-thread-per-task executor used by the orchestrator
 * to run agent execution loops without blocking platform threads.
 */
@Configuration
public class VirtualThreadConfig {

    @Bean
    public ExecutorService agentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
