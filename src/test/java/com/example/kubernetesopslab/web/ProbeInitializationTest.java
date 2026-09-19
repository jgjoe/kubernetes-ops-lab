package com.example.kubernetesopslab.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A freshly started application must answer both probes with 200 before anything touches the
 * process-local state. That is what makes a kubelet restart of a liveness-failing container
 * recover: the replacement process starts healthy again.
 *
 * <p>A fresh context is required here because other tests deliberately push the shared probe state
 * into the failing state, so this class dirties the cached context before it runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ProbeInitializationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void bothProbesReportHealthyOnAFreshStart() throws Exception {
        mockMvc.perform(get("/health/ready")).andExpect(status().isOk());
        mockMvc.perform(get("/health/live")).andExpect(status().isOk());
    }
}
