package com.programmersdiary.aidaemon.cli;

import com.programmersdiary.aidaemon.chat.ConversationService;
import com.programmersdiary.aidaemon.provider.ProviderConfigRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Scanner;

@Component
@Profile("cli")
public class InteractiveCli implements CommandLineRunner {

    private final ProviderConfigRepository providerRepository;
    private final ConversationService conversationService;

    public InteractiveCli(ProviderConfigRepository providerRepository,
                          ConversationService conversationService) {
        this.providerRepository = providerRepository;
        this.conversationService = conversationService;
    }

    @Override
    public void run(String... args) {
        var scanner = new Scanner(System.in);

        System.out.println("\n=== AI Daemon Interactive Chat ===\n");

        var providerId = selectProvider(scanner);
        if (providerId == null) return;

        var conversation = conversationService.create(providerId);
        System.out.println("Conversation started. Type /quit to exit, /new to start fresh.\n");

        while (true) {
            System.out.print("You: ");
            if (!scanner.hasNextLine()) break;
            var input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;
            if (input.equalsIgnoreCase("/quit")) break;
            if (input.equalsIgnoreCase("/new")) {
                providerId = selectProvider(scanner);
                if (providerId == null) break;
                conversation = conversationService.create(providerId);
                System.out.println("New conversation started.\n");
                continue;
            }

            try {
                var response = conversationService.sendMessage(conversation.id(), input);
                System.out.println("\nAI: " + response + "\n");
            } catch (Exception e) {
                System.out.println("\nError: " + e.getMessage() + "\n");
            }
        }

        System.out.println("Goodbye!");
    }

    private String selectProvider(Scanner scanner) {
        var providers = providerRepository.findAll();
        if (providers.isEmpty()) {
            System.out.println("No providers configured. Add one via POST /api/providers first.");
            return null;
        }

        System.out.println("Available providers:");
        for (int i = 0; i < providers.size(); i++) {
            var p = providers.get(i);
            System.out.printf("  %d) %s (%s, %s)%n", i + 1, p.name(), p.type(), p.model());
        }

        System.out.print("Select provider (number): ");
        if (!scanner.hasNextLine()) return null;
        var choice = scanner.nextLine().trim();

        try {
            int index = Integer.parseInt(choice) - 1;
            if (index >= 0 && index < providers.size()) {
                var selected = providers.get(index);
                System.out.println("Using: " + selected.name() + "\n");
                return selected.id();
            }
        } catch (NumberFormatException ignored) {
        }

        System.out.println("Invalid selection.");
        return null;
    }
}
