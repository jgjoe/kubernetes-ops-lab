package com.example.kubernetesopslab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * External configuration of the lab service.
 *
 * <p>{@code app.version} defaults to {@code v1} and is meant to be overridden per environment,
 * which is how a rollout changes the version reported by {@code GET /version} without a code
 * change: {@code APP_VERSION=v2} (environment variable, for example from a ConfigMap),
 * {@code --app.version=v2} (program argument), or {@code -Dapp.version=v2} (JVM system property).
 */
@ConfigurationProperties(prefix = "app")
public record LabProperties(@DefaultValue("v1") String version) {
}
