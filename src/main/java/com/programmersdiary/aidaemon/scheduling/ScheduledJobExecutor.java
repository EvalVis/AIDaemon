package com.programmersdiary.aidaemon.scheduling;

import com.programmersdiary.aidaemon.chat.ChatMessage;
import com.programmersdiary.aidaemon.chat.ChatService;
import com.programmersdiary.aidaemon.bot.BotService;
import com.programmersdiary.aidaemon.chat.ConversationService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Component
public class ScheduledJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobExecutor.class);
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
            .withZone(ZoneId.systemDefault());

    private final ScheduledJobRepository jobRepository;
    private final ObjectProvider<ChatService> chatServiceProvider;
    private final ObjectProvider<ConversationService> conversationServiceProvider;
    private final ObjectProvider<BotService> botServiceProvider;
    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();

    public ScheduledJobExecutor(ScheduledJobRepository jobRepository,
                                ObjectProvider<ChatService> chatServiceProvider,
                                ObjectProvider<ConversationService> conversationServiceProvider,
                                ObjectProvider<BotService> botServiceProvider,
                                TaskScheduler taskScheduler) {
        this.jobRepository = jobRepository;
        this.chatServiceProvider = chatServiceProvider;
        this.conversationServiceProvider = conversationServiceProvider;
        this.botServiceProvider = botServiceProvider;
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
            var bot = botServiceProvider.getObject().getBot(null);
            var meta = bot.streamRequestMetadata(messages, null);
            var contextMessages = bot.buildContext(messages);
            var chatResult = chatServiceProvider.getObject().streamAndCollect(job.providerId(), contextMessages, meta);
            var name = "[SJ]" + job.description() + "-" + NAME_TIME.format(Instant.now());
            var conversation = conversationServiceProvider.getObject().create(name, job.providerId());
            conversation.messages().add(ChatMessage.of("user", job.instruction()));
            conversation.messages().add(ChatMessage.of("assistant", chatResult.assistantContent()));
            conversationServiceProvider.getObject().save(conversation);
            log.info("Job '{}' executed, conversation {}", job.description(), conversation.id());
        } catch (Exception e) {
            log.error("Job '{}' failed: {}", job.description(), e.getMessage());
        }
    }
}
