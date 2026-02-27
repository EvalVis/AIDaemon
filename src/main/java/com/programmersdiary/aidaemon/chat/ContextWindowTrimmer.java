package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.bot.PersonalMemoryEntry;

import java.util.List;

public final class ContextWindowTrimmer {

    private ContextWindowTrimmer() {
    }

    public static List<ChatMessage> trimToLimit(List<ChatMessage> items, int charLimit) {
        if (items.isEmpty()) {
            return items;
        }
        if (charLimit <= 0) {
            return items.subList(items.size(), items.size());
        }
        int total = 0;
        int start = items.size();
        for (int i = items.size() - 1; i >= 0; i--) {
            String content = items.get(i).content();
            int len = content != null ? content.length() : 0;
            if (total + len > charLimit) {
                break;
            }
            total += len;
            start = i;
        }
        return items.subList(start, items.size());
    }

    public static List<PersonalMemoryEntry> trimToLimit(List<PersonalMemoryEntry> items, int charLimit) {
        if (items.isEmpty()) {
            return items;
        }
        if (charLimit <= 0) {
            return items.subList(items.size(), items.size());
        }
        int total = 0;
        int start = items.size();
        for (int i = items.size() - 1; i >= 0; i--) {
            String content = items.get(i).content();
            int len = content != null ? content.length() : 0;
            if (total + len > charLimit) {
                break;
            }
            total += len;
            start = i;
        }
        return items.subList(start, items.size());
    }
}
