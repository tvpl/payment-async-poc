package com.example.payments.sbus.config;

import com.example.payments.common.kafka.AvroSerde;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.time.Duration;

@Factory
final class ContractCodecFactory {

    @Bean(preDestroy = "close")
    @Singleton
    AvroSerde avroSerde(
            @Value("${apicurio.registry.url:`http://localhost:8085/apis/registry/v2`}")
            String registryUrl,
            @Value("${payments.avro.codec-pool-size:8}") int poolSize,
            @Value("${payments.avro.codec-acquire-timeout:250ms}") Duration acquireTimeout,
            @Value("${payments.avro.auto-register:true}") boolean autoRegister) {
        return new AvroSerde(registryUrl, poolSize, acquireTimeout, autoRegister);
    }
}
