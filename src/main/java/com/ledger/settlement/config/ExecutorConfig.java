package com.ledger.settlement.config;

import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

/**
 * The application executor, built from Executors.newVirtualThreadPerTaskExecutor().
 * The service had no pooled executor bean before this change (it relied on Tomcat's default
 * request pool), so there is no old pooled executor to remove. The bean name matches Spring
 * Boot's default so MVC async handling uses it.
 */
@Configuration
public class ExecutorConfig {

    @Bean(name = "applicationTaskExecutor")
    public AsyncTaskExecutor applicationTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}