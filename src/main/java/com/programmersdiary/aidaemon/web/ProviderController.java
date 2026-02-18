package com.programmersdiary.aidaemon.web;

import com.programmersdiary.aidaemon.provider.ProviderConfig;
import com.programmersdiary.aidaemon.provider.ProviderConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ProviderConfigRepository repository;

    public ProviderController(ProviderConfigRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<ProviderResponse> list() {
        return repository.findAll().stream()
                .map(ProviderResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderResponse create(@RequestBody CreateProviderRequest request) {
        var config = new ProviderConfig(
                UUID.randomUUID().toString(),
                request.name(),
                request.type(),
                request.apiKey(),
                request.baseUrl(),
                request.model()
        );
        return ProviderResponse.from(repository.save(config));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        if (!repository.deleteById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
