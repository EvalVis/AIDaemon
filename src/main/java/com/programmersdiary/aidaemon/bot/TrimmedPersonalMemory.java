package com.programmersdiary.aidaemon.bot;

import java.util.List;

public record TrimmedPersonalMemory(int startIndexInclusive, List<PersonalMemoryEntry> entries, int totalEntryCount) {
}
