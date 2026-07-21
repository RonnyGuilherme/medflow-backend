package com.medflow.orchestrator.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class KafkaConfig {

    public static final String TOPIC_APPOINTMENTS = "medflow.appointments";

    @Bean
    public NewTopic appointmentsTopic() {
        return TopicBuilder.name(TOPIC_APPOINTMENTS)
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> factory) {
        return new KafkaTemplate<>(factory);
    }
}
