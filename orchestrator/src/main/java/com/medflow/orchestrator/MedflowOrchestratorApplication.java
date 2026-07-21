package com.medflow.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MedflowOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MedflowOrchestratorApplication.class, args);
    }
}
