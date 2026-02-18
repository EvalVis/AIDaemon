package com.programmersdiary.aidaemon.skills;

import com.programmersdiary.aidaemon.scheduling.ScheduledJob;
import com.programmersdiary.aidaemon.scheduling.ScheduledJobExecutor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.UUID;
import java.util.stream.Collectors;

public class ChatTools {

    private final SkillsService skillsService;
    private final ScheduledJobExecutor jobExecutor;
    private final String currentProviderId;

    public ChatTools(SkillsService skillsService,
                     ScheduledJobExecutor jobExecutor,
                     String currentProviderId) {
        this.skillsService = skillsService;
        this.jobExecutor = jobExecutor;
        this.currentProviderId = currentProviderId;
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
}
