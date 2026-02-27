package com.programmersdiary.aidaemon.web;

import com.programmersdiary.aidaemon.bot.BotDefinition;
import com.programmersdiary.aidaemon.bot.BotService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bots")
public class BotController {

    private final BotService botService;

    public BotController(BotService botService) {
        this.botService = botService;
    }

    @GetMapping
    public List<BotDefinition> list() {
        return botService.listBots();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BotDefinition create(@RequestBody CreateBotRequest request) {
        return botService.create(request.name(), request.soul());
    }

    public record CreateBotRequest(String name, String soul) {
    }
}

