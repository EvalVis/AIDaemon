package com.programmersdiary.aidaemon.scheduling;

import java.time.Instant;

public record JobResult(String jobId, String description, Instant executedAt, String response) {
}
