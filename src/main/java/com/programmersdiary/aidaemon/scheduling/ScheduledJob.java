package com.programmersdiary.aidaemon.scheduling;

public record ScheduledJob(
        String id,
        String providerId,
        String cronExpression,
        String instruction,
        String description) {
}
