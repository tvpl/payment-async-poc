package com.example.fixture;

import com.example.platform.featurecontrol.model.FlagType;

public final class PublishedArtifactConsumer {
    private PublishedArtifactConsumer() {
    }

    public static FlagType booleanFlagType() {
        return FlagType.BOOLEAN;
    }
}
