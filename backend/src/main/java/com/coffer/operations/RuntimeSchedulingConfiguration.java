package com.coffer.operations;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Backlog recovery must not starve dependency sampling. Both schedulers have fixed thread counts. */
@Configuration(proxyBeanMethods = false)
public class RuntimeSchedulingConfiguration {
    @Bean @Primary public ThreadPoolTaskScheduler taskScheduler() { return scheduler("coffer-task-schedule-"); }
    @Bean public ThreadPoolTaskScheduler runtimeMonitorScheduler() { return scheduler("coffer-runtime-schedule-"); }
    private ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix); scheduler.setRemoveOnCancelPolicy(true); return scheduler;
    }
}
