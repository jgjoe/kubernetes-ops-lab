package com.example.kubernetesopslab.web;

import com.example.kubernetesopslab.config.LabProperties;
import com.example.kubernetesopslab.health.ProbeState;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The only HTTP surface of the lab service: service identity, version, and the two probe paths.
 *
 * <p>GET reports the current probe state (200 healthy, 503 otherwise). POST changes the state and
 * then reports the resulting state with the same status convention, so a POST is never mistaken
 * for a healthy answer when the probe the cluster sees is failing.
 */
@RestController
public class OperationalController {

    private static final String SERVICE_NAME = "kubernetes-ops-lab";

    private final ProbeState probeState;
    private final LabProperties properties;

    public OperationalController(ProbeState probeState, LabProperties properties) {
        this.probeState = probeState;
        this.properties = properties;
    }

    @GetMapping("/")
    public String index() {
        return SERVICE_NAME;
    }

    @GetMapping("/version")
    public String version() {
        return properties.version();
    }

    @GetMapping("/health/ready")
    public ResponseEntity<String> readiness() {
        return probeResponse(probeState.isReady(), "ready", "not-ready");
    }

    @PostMapping("/health/ready")
    public ResponseEntity<String> updateReadiness(@RequestParam("ready") boolean ready) {
        probeState.setReady(ready);
        return probeResponse(ready, "ready", "not-ready");
    }

    @GetMapping("/health/live")
    public ResponseEntity<String> liveness() {
        return probeResponse(probeState.isLive(), "alive", "not-alive");
    }

    @PostMapping("/health/live")
    public ResponseEntity<String> updateLiveness(@RequestParam("live") boolean live) {
        probeState.setLive(live);
        return probeResponse(live, "alive", "not-alive");
    }

    private static ResponseEntity<String> probeResponse(boolean healthy, String healthyBody, String unhealthyBody) {
        return healthy
                ? ResponseEntity.ok(healthyBody)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(unhealthyBody);
    }
}
