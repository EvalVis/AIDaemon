package com.programmersdiary.aidaemon.skills;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.programmersdiary.aidaemon.chat.ChatMessage;
import com.programmersdiary.aidaemon.bot.BotRepository;
import com.programmersdiary.aidaemon.bot.PersonalMemoryEntry;
import com.programmersdiary.aidaemon.scheduling.ScheduledJob;
import com.programmersdiary.aidaemon.scheduling.ScheduledJobExecutor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ChatTools {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SkillsService skillsService;
    private final ScheduledJobExecutor jobExecutor;
    private final String currentProviderId;
    private final List<ChatMessage> conversationMessages;
    private final int charsContextWindow;
    private final String botName;
    private final BotRepository botRepository;

    public ChatTools(SkillsService skillsService,
                     ScheduledJobExecutor jobExecutor,
                     String currentProviderId) {
        this(skillsService, jobExecutor, currentProviderId, null, 0, null, null);
    }

    public ChatTools(SkillsService skillsService,
                     ScheduledJobExecutor jobExecutor,
                     String currentProviderId,
                     List<ChatMessage> conversationMessages,
                     int charsContextWindow) {
        this(skillsService, jobExecutor, currentProviderId, conversationMessages, charsContextWindow, null, null);
    }

    public ChatTools(SkillsService skillsService,
                     ScheduledJobExecutor jobExecutor,
                     String currentProviderId,
                     List<ChatMessage> conversationMessages,
                     int charsContextWindow,
                     String botName,
                     BotRepository botRepository) {
        this.skillsService = skillsService;
        this.jobExecutor = jobExecutor;
        this.currentProviderId = currentProviderId;
        this.conversationMessages = conversationMessages != null ? List.copyOf(conversationMessages) : null;
        this.charsContextWindow = charsContextWindow;
        this.botName = botName;
        this.botRepository = botRepository;
    }

    @Tool(description = "Save information to persistent memory. Use when the user asks you to remember something.")
    public String saveMemory(
            @ToolParam(description = "Short descriptive key") String key,
            @ToolParam(description = "The value to remember") String value) {
        skillsService.saveMemory(key, value);
        return "Saved to memory: " + key + " = " + value;
    }

    @Tool(description = "Read a file from a skill folder. Path is relative to the skills root, e.g. 'github-cli/instructions.md'.")
    public String readSkillFile(
            @ToolParam(description = "Relative path within the skills folder, e.g. 'skill-name/file.md'") String relativePath) {
        return skillsService.readFile(relativePath);
    }

    @Tool(description = "List all available skills (top-level folders in the skills directory).")
    public String listSkills() {
        var skills = skillsService.listSkills();
        if (skills.isEmpty()) {
            return "No skills installed.";
        }
        return "Available skills:\n" + skills.stream()
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "List all files inside a specific skill folder.")
    public String listSkillFiles(
            @ToolParam(description = "Name of the skill folder") String skillName) {
        var files = skillsService.listSkillFiles(skillName);
        if (files.isEmpty()) {
            return "No files found for skill: " + skillName;
        }
        return "Files in " + skillName + ":\n" + files.stream()
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Schedule a recurring or one-time job. The job will run at the specified frequency and execute the given instruction by sending it to the AI.")
    public String scheduleJob(
            @ToolParam(description = "Cron expression for scheduling. Examples: '0 */5 * * * *' for every 5 minutes, '0 0 9 * * *' for daily at 9am, '0 0 0 1 * *' for monthly") String cronExpression,
            @ToolParam(description = "The instruction/prompt that will be sent to the AI when the job runs") String instruction,
            @ToolParam(description = "Short human-readable description of what this job does") String description) {
        var job = new ScheduledJob(
                UUID.randomUUID().toString(),
                currentProviderId,
                cronExpression,
                instruction,
                description);
        jobExecutor.schedule(job);
        return "Job scheduled: " + description + " (id: " + job.id() + ", cron: " + cronExpression + ")";
    }

    @Tool(description = "Cancel a scheduled job by its ID.")
    public String cancelJob(
            @ToolParam(description = "The job ID to cancel") String jobId) {
        if (jobExecutor.cancel(jobId)) {
            return "Job cancelled: " + jobId;
        }
        return "Job not found: " + jobId;
    }

    @Tool(description = "List all currently scheduled jobs.")
    public String listJobs() {
        var jobs = jobExecutor.getJobs();
        if (jobs.isEmpty()) {
            return "No scheduled jobs.";
        }
        return jobs.stream()
                .map(j -> "- " + j.description() + " (id: " + j.id() + ", cron: " + j.cronExpression() + ")")
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Retrieve older conversation messages by index range. Provide startIndex (inclusive); optionally endIndex (exclusive). If endIndex is omitted, returns all messages from startIndex to the end. Result is capped by context window. Returns: startIndex, actualEndIndex (exclusive), and the message list.")
    public String retrieveOlderMessages(
            @ToolParam(description = "Start index (inclusive), 0-based") int startIndexInclusive,
            @ToolParam(description = "End index (exclusive). Omit to include all from startIndex to end") Integer endIndexExclusive) {
        if (conversationMessages == null || conversationMessages.isEmpty()) {
            return "No conversation context available.";
        }
        int size = conversationMessages.size();
        if (startIndexInclusive < 0 || startIndexInclusive >= size) {
            return "Invalid startIndexInclusive: " + startIndexInclusive + ". Conversation has " + size + " messages (indices 0 to " + (size - 1) + ").";
        }
        int end = endIndexExclusive == null ? size : Math.min(endIndexExclusive, size);
        if (end <= startIndexInclusive) {
            return "Invalid range: endIndexExclusive must be greater than startIndexInclusive.";
        }
        int actualEnd = startIndexInclusive;
        int totalChars = 0;
        boolean hasLimit = charsContextWindow > 0;
        for (int i = startIndexInclusive; i < end; i++) {
            String displayContent = contentWithoutReasoningAndTools(conversationMessages.get(i));
            int len = displayContent.length();
            if (hasLimit && totalChars + len > charsContextWindow) {
                break;
            }
            totalChars += len;
            actualEnd = i + 1;
        }
        var slice = conversationMessages.subList(startIndexInclusive, actualEnd);
        var lines = new ArrayList<String>();
        for (int i = 0; i < slice.size(); i++) {
            var m = slice.get(i);
            String displayContent = contentWithoutReasoningAndTools(m);
            lines.add("[" + (startIndexInclusive + i) + "] " + m.role() + ": " + displayContent);
        }
        return "startIndex: " + startIndexInclusive + ", actualEndIndex: " + actualEnd + ", messages:\n" + String.join("\n", lines);
    }

    @Tool(description = "Retrieve older personal memory entries by message index range (named bots only). Provide startIndexInclusive (message index); optionally endIndexExclusive. If endIndexExclusive is omitted, returns all entries from startIndex to the end. Returns: startIndex, actualEndIndex (exclusive), and the entry list.")
    public String retrieveOlderPersonalMemory(
            @ToolParam(description = "Start message index (inclusive), 0-based") int startIndexInclusive,
            @ToolParam(description = "End message index (exclusive). Omit to include all from startIndex to end") Integer endIndexExclusive) {
        if (botName == null || botName.isBlank() || "default".equalsIgnoreCase(botName) || botRepository == null) {
            return "Personal memory is only available for named bots.";
        }
        if (!botRepository.exists(botName)) {
            return "No personal memory for this bot.";
        }
        var entries = botRepository.loadPersonalMemory(botName);
        if (entries.isEmpty()) {
            return "No personal memory for this bot.";
        }
        int size = entries.size();
        if (startIndexInclusive < 0 || startIndexInclusive >= size) {
            return "Invalid startIndexInclusive: " + startIndexInclusive + ". Personal memory has " + size + " entries (indices 0 to " + (size - 1) + ").";
        }
        int end = endIndexExclusive == null ? size : Math.min(endIndexExclusive, size);
        if (end <= startIndexInclusive) {
            return "Invalid range: endIndexExclusive must be greater than startIndexInclusive.";
        }
        var slice = entries.subList(startIndexInclusive, end);
        var lines = new ArrayList<String>();
        for (int i = 0; i < slice.size(); i++) {
            var e = slice.get(i);
            lines.add("[" + (startIndexInclusive + i) + "] " + e.role() + ": " + (e.content() != null ? e.content() : ""));
        }
        return "startIndex: " + startIndexInclusive + ", actualEndIndex: " + end + ", entries:\n" + String.join("\n", lines);
    }

    private static String contentWithoutReasoningAndTools(ChatMessage message) {
        String content = message.content();
        if (content == null) return "";
        if (!"assistant".equals(message.role())) return content;
        if (!content.trim().startsWith("{\"parts\":")) return content;
        try {
            @SuppressWarnings("unchecked")
            var map = OBJECT_MAPPER.readValue(content, Map.class);
            var parts = (List<?>) map.get("parts");
            if (parts == null) return content;
            var sb = new StringBuilder();
            for (var o : parts) {
                if (!(o instanceof Map<?, ?> p)) continue;
                var type = p.get("type");
                if (!"answer".equals(type)) continue;
                var partContent = p.get("content");
                if (partContent != null) sb.append(partContent.toString());
            }
            return sb.toString();
        } catch (JsonProcessingException | ClassCastException e) {
            return content;
        }
    }
}
