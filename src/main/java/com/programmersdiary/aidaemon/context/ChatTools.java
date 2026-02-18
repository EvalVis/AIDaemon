package com.programmersdiary.aidaemon.context;

import com.programmersdiary.aidaemon.scheduling.ScheduledJob;
import com.programmersdiary.aidaemon.scheduling.ScheduledJobExecutor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.UUID;
import java.util.stream.Collectors;

public class ChatTools {

    private final ContextService contextService;
    private final ScheduledJobExecutor jobExecutor;
    private final String currentProviderId;

    public ChatTools(ContextService contextService,
                     ScheduledJobExecutor jobExecutor,
                     String currentProviderId) {
        this.contextService = contextService;
        this.jobExecutor = jobExecutor;
        this.currentProviderId = currentProviderId;
    }

    @Tool(description = "Save information to persistent memory. Use when the user asks you to remember something.")
    public String saveMemory(
            @ToolParam(description = "Short descriptive key") String key,
            @ToolParam(description = "The value to remember") String value) {
        contextService.saveMemory(key, value);
        return "Saved to memory: " + key + " = " + value;
    }

    @Tool(description = "Read a file from the context folder.")
    public String readContextFile(
            @ToolParam(description = "Name of the file to read") String filename) {
        return contextService.readFile(filename);
    }

    @Tool(description = "List available files in the context folder.")
    public String listContextFiles() {
        var files = contextService.listFiles();
        if (files.isEmpty()) {
            return "No files in context folder.";
        }
        return "Available files:\n" + files.stream()
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
}
