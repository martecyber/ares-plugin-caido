package com.martecyber.plugins.caido;

import com.martecyber.ares.plugins.PluginComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.OffsetDateTime;

/**
 * Finds due {@link CaidoApiTask}s and executes them via {@link CaidoApiTaskExecutor}.
 * Tasks run sequentially to avoid concurrent selectProject calls on the same Caido instance.
 */
public class CaidoApiTaskScheduler implements PluginComponent {

    private static final Logger log = LoggerFactory.getLogger(CaidoApiTaskScheduler.class);

    private final CaidoApiTaskRepository taskRepo;
    private final CaidoApiTaskExecutor executor;

    public CaidoApiTaskScheduler(CaidoApiTaskRepository taskRepo, CaidoApiTaskExecutor executor) {
        this.taskRepo = taskRepo;
        this.executor = executor;
    }

    @Scheduled(fixedDelay = 60_000)
    public void runDue() {
        var due = taskRepo.findAllDue(OffsetDateTime.now());
        if (due.isEmpty()) return;
        log.info("Running {} due Caido API task(s)", due.size());
        for (CaidoApiTask task : due) {
            try {
                executor.execute(task);
            } catch (Exception e) {
                log.error("Unexpected error executing Caido API task {}: {}", task.getId(), e.getMessage(), e);
            }
        }
    }
}
