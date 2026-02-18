package com.programmersdiary.aidaemon.skills;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ShellAccessService {

    private final AtomicBoolean enabled;

    public ShellAccessService(@Value("${aidaemon.shell-access:false}") boolean enabled) {
        this.enabled = new AtomicBoolean(enabled);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }
}
