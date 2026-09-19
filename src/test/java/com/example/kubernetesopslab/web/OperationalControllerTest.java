package com.example.kubernetesopslab.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OperationalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rootReportsServiceIdentity() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string("kubernetes-ops-lab"));
    }

    @Test
    void versionReportsDefaultVersion() throws Exception {
        mockMvc.perform(get("/version"))
                .andExpect(status().isOk())
                .andExpect(content().string("v1"));
    }

    @Test
    void readinessFailureAndRecoveryAreVisibleOnTheProbePath() throws Exception {
        mockMvc.perform(post("/health/ready").param("ready", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string("ready"));

        mockMvc.perform(post("/health/ready").param("ready", "false"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("not-ready"));
        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("not-ready"));

        mockMvc.perform(post("/health/ready").param("ready", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string("ready"));
        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(content().string("ready"));
    }

    @Test
    void livenessFailureAndRecoveryAreVisibleOnTheProbePath() throws Exception {
        mockMvc.perform(post("/health/live").param("live", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string("alive"));

        mockMvc.perform(post("/health/live").param("live", "false"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("not-alive"));
        mockMvc.perform(get("/health/live"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("not-alive"));

        mockMvc.perform(post("/health/live").param("live", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string("alive"));
        mockMvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(content().string("alive"));
    }

    @Test
    void failingReadinessLeavesLivenessHealthy() throws Exception {
        mockMvc.perform(post("/health/ready").param("ready", "true")).andExpect(status().isOk());
        mockMvc.perform(post("/health/live").param("live", "true")).andExpect(status().isOk());

        mockMvc.perform(post("/health/ready").param("ready", "false"))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(content().string("alive"));

        mockMvc.perform(post("/health/ready").param("ready", "true")).andExpect(status().isOk());
    }

    @Test
    void invalidProbeInputIsRejectedWithoutChangingState() throws Exception {
        mockMvc.perform(post("/health/ready").param("ready", "true")).andExpect(status().isOk());
        mockMvc.perform(post("/health/live").param("live", "true")).andExpect(status().isOk());

        mockMvc.perform(post("/health/ready").param("ready", "maybe")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/health/live").param("live", "2")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/health/ready")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/health/live")).andExpect(status().isBadRequest());

        mockMvc.perform(get("/health/ready")).andExpect(status().isOk());
        mockMvc.perform(get("/health/live")).andExpect(status().isOk());
    }
}
