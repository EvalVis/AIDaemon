package com.programmersdiary.aidaemon.web;

import com.programmersdiary.aidaemon.skills.ShellAccessService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/shell-access")
public class ShellAccessController {

    private final ShellAccessService shellAccessService;

    public ShellAccessController(ShellAccessService shellAccessService) {
        this.shellAccessService = shellAccessService;
    }

    @GetMapping
    public Map<String, Boolean> status() {
        return Map.of("enabled", shellAccessService.isEnabled());
    }

    @PostMapping("/enable")
    public Map<String, Boolean> enable() {
        shellAccessService.setEnabled(true);
        return Map.of("enabled", true);
    }

    @PostMapping("/disable")
    public Map<String, Boolean> disable() {
        shellAccessService.setEnabled(false);
        return Map.of("enabled", false);
    }
}
