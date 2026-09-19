package com.example.kubernetesopslab.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /version must follow external configuration, which is how a rollout changes the reported
 * version without a code change (environment from a ConfigMap, program argument, or system
 * property all bind to {@code app.version}).
 */
@SpringBootTest(properties = "app.version=v2")
@AutoConfigureMockMvc
class VersionConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void versionFollowsExternalConfiguration() throws Exception {
        mockMvc.perform(get("/version"))
                .andExpect(status().isOk())
                .andExpect(content().string("v2"));
    }
}
