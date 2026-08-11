package com.example.platform.featurecontrol.store;

import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.spi.FlagSource;
import io.micronaut.context.annotation.Primary;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.Optional;

/**
 * The {@link FlagSource} the resolver actually uses: Redis (dynamic override) first, then the YAML
 * baseline. This layering is the professional pattern — ship a safe default in config, flip it at
 * runtime via Redis when needed, and always have the baseline to fall back to. The Redis source is
 * optional (apps without Redis are YAML-only), so this stays a {@code @Nullable} dependency.
 */
@Singleton
@Primary
public class CompositeFlagSource implements FlagSource {

    private final StaticFlagSource baseline;
    @Nullable
    private final RedisFlagSource dynamic;

    public CompositeFlagSource(StaticFlagSource baseline, @Nullable RedisFlagSource dynamic) {
        this.baseline = baseline;
        this.dynamic = dynamic;
    }

    @Override
    public Optional<FlagDefinition> find(String name) {
        if (dynamic != null) {
            Optional<FlagDefinition> override = dynamic.find(name);
            if (override.isPresent()) {
                return override;
            }
        }
        return baseline.find(name);
    }
}
