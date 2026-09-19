package com.example.kubernetesopslab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KubernetesOpsLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(KubernetesOpsLabApplication.class, args);
    }
}
