package com.programmersdiary.aidaemon.scheduling;

import com.programmersdiary.aidaemon.chat.ChatMessage;
import com.programmersdiary.aidaemon.chat.ChatService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

@Component
public class ScheduledJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobExecutor.class);
    private static final int MAX_RESULTS = 100;

    private final ScheduledJobRepository jobRepository;
    private final ObjectProvider<ChatService> chatServiceProvider;
    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();
    private final List<JobResult> results = new CopyOnWriteArrayList<>();

    public ScheduledJobExecutor(ScheduledJobRepository jobRepository,
                                ObjectProvider<ChatService> chatServiceProvider,
                                TaskScheduler taskScheduler) {
        this.jobRepository = jobRepository;
        this.chatServiceProvider = chatServiceProvider;
        this.taskScheduler = taskScheduler;
    }

    @PostConstruct
    void restoreJobs() {
        jobRepository.findAll().forEach(this::scheduleInternal);
    }

    public void schedule(ScheduledJob job) {
        jobRepository.save(job);
        scheduleInternal(job);
    }

    public boolean cancel(String jobId) {
        var future = activeFutures.remove(jobId);
        if (future != null) {
            future.cancel(false);
        }
        return jobRepository.deleteById(jobId);
    }

    public List<ScheduledJob> getJobs() {
        return jobRepository.findAll();
    }

    public List<JobResult> getResults() {
        return List.copyOf(results);
    }

    private void scheduleInternal(ScheduledJob job) {
        var future = taskScheduler.schedule(
                () -> executeJob(job),
                new CronTrigger(job.cronExpression()));
        activeFutures.put(job.id(), future);
        log.info("Scheduled job '{}' with cron '{}'", job.description(), job.cronExpression());
    }

    private void executeJob(ScheduledJob job) {
        try {
            var messages = List.of(ChatMessage.of("user", job.instruction()));
            var chatResult = chatServiceProvider.getObject().chat(job.providerId(), messages);
            var result = new JobResult(job.id(), job.description(), Instant.now(), chatResult.response());
            results.add(result);
            if (results.size() > MAX_RESULTS) {
                results.removeFirst();
            }
            log.info("Job '{}' executed: {}", job.description(), chatResult.response());
        } catch (Exception e) {
            log.error("Job '{}' failed: {}", job.description(), e.getMessage());
            results.add(new JobResult(job.id(), job.description(), Instant.now(), "ERROR: " + e.getMessage()));
        }
    }
}
