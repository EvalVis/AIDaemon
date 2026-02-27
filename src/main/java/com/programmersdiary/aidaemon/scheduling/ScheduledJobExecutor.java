package com.programmersdiary.aidaemon.scheduling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.programmersdiary.aidaemon.chat.ChatMessage;
import com.programmersdiary.aidaemon.chat.ChatService;
import com.programmersdiary.aidaemon.chat.ChatContextBuilder;
import com.programmersdiary.aidaemon.chat.ContextConfig;
import com.programmersdiary.aidaemon.chat.ConversationService;
import com.programmersdiary.aidaemon.chat.StreamRequestMetadata;
import com.programmersdiary.aidaemon.chat.StreamChunk;
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
    private final ObjectProvider<ChatContextBuilder> contextBuilderProvider;
    private final ObjectProvider<ContextConfig> contextConfigProvider;
    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();

    public ScheduledJobExecutor(ScheduledJobRepository jobRepository,
                                ObjectProvider<ChatService> chatServiceProvider,
                                ObjectProvider<ConversationService> conversationServiceProvider,
                                ObjectProvider<ChatContextBuilder> contextBuilderProvider,
                                ObjectProvider<ContextConfig> contextConfigProvider,
                                TaskScheduler taskScheduler) {
        this.jobRepository = jobRepository;
        this.chatServiceProvider = chatServiceProvider;
        this.conversationServiceProvider = conversationServiceProvider;
        this.contextBuilderProvider = contextBuilderProvider;
        this.contextConfigProvider = contextConfigProvider;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private void executeJob(ScheduledJob job) {
        try {
            var messages = List.of(ChatMessage.of("user", job.instruction()));
            var contextBuilder = contextBuilderProvider.getObject();
            var contextConfig = contextConfigProvider.getObject();
            var meta = new StreamRequestMetadata(messages, null, null, contextConfig.charsLimit());
            var contextMessages = contextBuilder.buildMessages(messages, null, contextConfig.charsLimit(), 0, contextConfig.systemInstructions());
            var chatResult = chatServiceProvider.getObject().streamAndCollect(job.providerId(), contextMessages, meta);
            var name = "[SJ]" + job.description() + "-" + NAME_TIME.format(Instant.now());
            var conversation = conversationServiceProvider.getObject().create(name, job.providerId());
            conversation.messages().add(ChatMessage.of("user", job.instruction()));
            conversation.messages().addAll(chatResult.toolMessages());
            var assistantContent = buildAssistantContent(chatResult);
            conversation.messages().add(ChatMessage.of("assistant", assistantContent));
            conversationServiceProvider.getObject().save(conversation);
            log.info("Job '{}' executed, conversation {}", job.description(), conversation.id());
        } catch (Exception e) {
            log.error("Job '{}' failed: {}", job.description(), e.getMessage());
        }
    }

    private static String buildAssistantContent(com.programmersdiary.aidaemon.chat.ChatResult chatResult) {
        if (chatResult.orderedParts() != null && !chatResult.orderedParts().isEmpty()) {
            try {
                return OBJECT_MAPPER.writeValueAsString(Map.of("parts", chatResult.orderedParts()));
            } catch (JsonProcessingException e) {
                return chatResult.response();
            }
        }
        return chatResult.response();
    }
}
