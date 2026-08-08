package com.mac.scheduler.config;

import com.mac.scheduler.config.properties.HttpTaskProperties;
import com.mac.scheduler.config.properties.SchedulerEngineProperties;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    HttpClient schedulerHttpClient(HttpTaskProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean(name = "schedulerVirtualThreadExecutor", destroyMethod = "close")
    ExecutorService schedulerVirtualThreadExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("scheduler-worker-", 0).factory());
    }

    @Bean
    Semaphore taskExecutionPermits(SchedulerEngineProperties properties) {
        return new Semaphore(properties.maxParallelism());
    }
}
