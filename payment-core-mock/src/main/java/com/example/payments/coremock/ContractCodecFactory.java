package com.example.payments.coremock;

import com.example.payments.common.kafka.AvroSerde;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

@Factory
final class ContractCodecFactory {

    @Bean(preDestroy = "close")
    @Singleton
    AvroSerde avroSerde(
            @Value("${apicurio.registry.url:`http://localhost:8085/apis/registry/v2`}")
            String registryUrl) {
        return new AvroSerde(registryUrl);
    }
}
