package com.programmersdiary.aidaemon.delegation;

import com.programmersdiary.aidaemon.chat.ChatMessage;
import com.programmersdiary.aidaemon.chat.ChatService;
import com.programmersdiary.aidaemon.chat.Conversation;
import com.programmersdiary.aidaemon.chat.ConversationRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

@Service
@ConditionalOnProperty(name = "aidaemon.delegation-enabled", havingValue = "true")
public class DelegationService {

    private static final Logger log = LoggerFactory.getLogger(DelegationService.class);

    private final ConversationRepository conversationRepository;
    private final ObjectProvider<ChatService> chatServiceProvider;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, ReentrantLock> parentLocks = new ConcurrentHashMap<>();

    public DelegationService(ConversationRepository conversationRepository,
                             ObjectProvider<ChatService> chatServiceProvider) {
        this.conversationRepository = conversationRepository;
        this.chatServiceProvider = chatServiceProvider;
    }

    public void startSubAgents(List<String> pendingSubConversationIds) {
        for (var subId : pendingSubConversationIds) {
            executor.submit(() -> executeSubAgent(subId));
        }
    }

    private void executeSubAgent(String subConversationId) {
        try {
            var sub = conversationRepository.findById(subConversationId).orElseThrow(
                    () -> new IllegalStateException("Sub-conversation not found: " + subConversationId));
            log.info("Executing sub-agent for '{}' ({})", sub.name(), subConversationId);

            var result = chatServiceProvider.getObject()
                    .chat(sub.providerId(), sub.messages(), subConversationId);
            sub.messages().addAll(result.toolMessages());
            sub.messages().add(ChatMessage.of("assistant", result.response()));
            conversationRepository.save(sub);

            log.info("Sub-agent '{}' completed", sub.name());

            if (!result.pendingSubConversationIds().isEmpty()) {
                startSubAgents(result.pendingSubConversationIds());
            } else {
                wakeUpParent(sub.parentConversationId());
            }
        } catch (Exception e) {
            log.error("Sub-agent execution failed for {}: {}", subConversationId, e.getMessage(), e);
            var sub = conversationRepository.findById(subConversationId).orElse(null);
            if (sub != null) {
                sub.messages().add(ChatMessage.of("assistant", "[Error] " + e.getMessage()));
                conversationRepository.save(sub);
                wakeUpParent(sub.parentConversationId());
            }
        }
    }

    private void wakeUpParent(String parentId) {
        if (parentId == null) return;

        var lock = parentLocks.computeIfAbsent(parentId, k -> new ReentrantLock());
        lock.lock();
        try {
            var parent = conversationRepository.findById(parentId).orElse(null);
            if (parent == null) {
                log.warn("Parent conversation not found: {}", parentId);
                return;
            }

            var subs = conversationRepository.findByParentId(parentId);
            boolean allComplete = subs.stream()
                    .filter(s -> !s.messages().isEmpty())
                    .allMatch(s -> "assistant".equals(s.messages().getLast().role()));

            log.info("Waking up parent '{}' ({}), all subs complete: {}", parent.name(), parentId, allComplete);

            parent.messages().add(ChatMessage.of("user", buildStatusMessage(subs, allComplete)));

            var result = chatServiceProvider.getObject()
                    .chat(parent.providerId(), parent.messages(), parentId);
            parent.messages().addAll(result.toolMessages());
            parent.messages().add(ChatMessage.of("assistant", result.response()));
            conversationRepository.save(parent);

            if (!result.pendingSubConversationIds().isEmpty()) {
                startSubAgents(result.pendingSubConversationIds());
                return;
            }

            var updatedSubs = conversationRepository.findByParentId(parentId);
            boolean nowAllComplete = updatedSubs.stream()
                    .filter(s -> !s.messages().isEmpty())
                    .allMatch(s -> "assistant".equals(s.messages().getLast().role()));

            if (nowAllComplete && parent.parentConversationId() != null) {
                executor.submit(() -> wakeUpParent(parent.parentConversationId()));
            }
        } finally {
            lock.unlock();
        }
    }

    private String buildStatusMessage(List<Conversation> subs, boolean allComplete) {
        var sb = new StringBuilder("[Delegation Status Update]\n");
        sb.append("Your sub-agents have been working. Current status:\n\n");

        for (var sub : subs) {
            boolean complete = !sub.messages().isEmpty()
                    && "assistant".equals(sub.messages().getLast().role());
            sb.append("--- Sub-agent: ").append(sub.name())
                    .append(" (").append(sub.id()).append(") ---\n");
            sb.append("Status: ").append(complete ? "COMPLETED" : "PENDING").append("\n");

            for (var msg : sub.messages()) {
                if (!"tool".equals(msg.role())) {
                    sb.append(msg.role().toUpperCase()).append(": ").append(msg.content()).append("\n");
                }
            }
            sb.append("\n");
        }

        if (allComplete) {
            sb.append("All sub-agents have completed. Review the results and provide your final response to the user. ")
                    .append("If any sub-agent's work needs revision, use addWorkToSubAgent to send additional instructions.")
                    .append("You can always create new delegates if you see more tasks that can be split knowing the current state.");
        } else {
            sb.append("Some sub-agents are still working. Review completed work. ")
                    .append("Use addWorkToSubAgent if any completed sub-agent needs revision. ")
                    .append("You will be woken up again when more sub-agents complete.")
                    .append("But if you think you don't need more data you can respond to your caller without waiting for other sub-agents.");
        }

        return sb.toString();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
