package com.example.platform.featurecontrol.spi;

import com.example.platform.featurecontrol.model.FlagDefinition;

import java.util.Optional;

/**
 * A source of {@link FlagDefinition}s by name. Implementations back the resolver: a static one from
 * YAML (baseline), a dynamic one from Redis (runtime overrides), and a composite that layers them.
 * Keeping this an interface is what lets each of the 30+ apps swap or add sources without touching
 * the resolver.
 */
public interface FlagSource {

    Optional<FlagDefinition> find(String name);
}
