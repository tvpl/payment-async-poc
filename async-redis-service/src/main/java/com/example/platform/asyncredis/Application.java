package com.example.platform.asyncredis;

import io.micronaut.runtime.Micronaut;

/**
 * Standalone example of synchronous-over-asynchronous <strong>without Kafka</strong>: the API enqueues
 * work on a Redis Stream and blocks (on a virtual thread) monitoring Redis until the worker releases
 * the response. See {@code docs/17-async-sync-redis.md}.
 */
public class Application {

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
