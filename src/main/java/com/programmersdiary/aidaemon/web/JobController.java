package com.programmersdiary.aidaemon.web;

import com.programmersdiary.aidaemon.scheduling.JobResult;
import com.programmersdiary.aidaemon.scheduling.ScheduledJob;
import com.programmersdiary.aidaemon.scheduling.ScheduledJobExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final ScheduledJobExecutor jobExecutor;

    public JobController(ScheduledJobExecutor jobExecutor) {
        this.jobExecutor = jobExecutor;
    }

    @GetMapping
    public List<ScheduledJob> listJobs() {
        return jobExecutor.getJobs();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelJob(@PathVariable String id) {
        if (!jobExecutor.cancel(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    @GetMapping("/results")
    public List<JobResult> getResults() {
        return jobExecutor.getResults();
    }
}
