package com.example.platform.featurecontrol.store;

import com.example.platform.featurecontrol.config.FlagDefinitionProperties;
import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.spi.FlagSource;
import jakarta.inject.Singleton;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Baseline {@link FlagSource} built from {@code platform.features.flags.*} in YAML. This is the
 * safe default that ships with each deploy; the Redis source layers dynamic overrides on top. If
 * everything else fails, these values still apply — so behavior is never undefined.
 */
@Singleton
public class StaticFlagSource implements FlagSource {

    private final Map<String, FlagDefinition> definitions = new HashMap<>();

    public StaticFlagSource(List<FlagDefinitionProperties> flags) {
        for (FlagDefinitionProperties flag : flags) {
            definitions.put(flag.getName(), flag.toDefinition());
        }
    }

    @Override
    public Optional<FlagDefinition> find(String name) {
        return Optional.ofNullable(definitions.get(name));
    }
}
