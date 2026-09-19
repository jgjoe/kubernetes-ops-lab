package com.example.kubernetesopslab.health;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/**
 * Probe state kept in process-local memory only.
 *
 * <p>Both values start as {@code true} on every JVM start. That is what makes an intentional
 * liveness failure transient: kubelet restarts the container, the new process answers
 * {@code /health/live} with 200 again, and the Pod returns to Ready without any external reset.
 * The application never exits or restarts itself.
 */
@Component
public class ProbeState {

    private final AtomicBoolean ready = new AtomicBoolean(true);
    private final AtomicBoolean live = new AtomicBoolean(true);

    public boolean isReady() {
        return ready.get();
    }

    public void setReady(boolean value) {
        ready.set(value);
    }

    public boolean isLive() {
        return live.get();
    }

    public void setLive(boolean value) {
        live.set(value);
    }
}
