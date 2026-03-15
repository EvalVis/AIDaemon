package com.programmersdiary.aidaemon.scheduling;

import com.programmersdiary.aidaemon.chat.ChatMessage;
import com.programmersdiary.aidaemon.chat.ChatService;
import com.programmersdiary.aidaemon.chat.ContextConfig;
import com.programmersdiary.aidaemon.chat.StreamRequestMetadata;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Component
public class ScheduledJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobExecutor.class);

    private final ScheduledJobRepository jobRepository;
    private final ObjectProvider<ChatService> chatServiceProvider;
    private final ContextConfig contextConfig;
    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();

    public ScheduledJobExecutor(ScheduledJobRepository jobRepository,
                                ObjectProvider<ChatService> chatServiceProvider,
                                ContextConfig contextConfig,
                                TaskScheduler taskScheduler) {
        this.jobRepository = jobRepository;
        this.chatServiceProvider = chatServiceProvider;
        this.contextConfig = contextConfig;
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
        return List.of();
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
            var contextMessages = List.of(
                    (org.springframework.ai.chat.messages.Message) SystemMessage.builder()
                            .text(contextConfig.systemInstructions()).build(),
                    new UserMessage(job.instruction()));
            var meta = new StreamRequestMetadata(messages, null, null, contextConfig.charsLimit());
            var chatResult = chatServiceProvider.getObject().streamAndCollect(job.providerId(), contextMessages, meta);
            log.info("Job '{}' completed: {}", job.description(), chatResult.response());
        } catch (Exception e) {
            log.error("Job '{}' failed: {}", job.description(), e.getMessage());
        }
    }
}
