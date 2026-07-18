package com.example.platform.featurecontrol.annotation;

/** Raised by {@link FeatureGate} when the required flag is off for the caller. */
public class FeatureDisabledException extends RuntimeException {

    private final String flag;
    private final boolean notFound;

    public FeatureDisabledException(String flag, boolean notFound) {
        super("Feature '" + flag + "' is not available");
        this.flag = flag;
        this.notFound = notFound;
    }

    public String flag() {
        return flag;
    }

    /** Whether to hide the feature as 404 (true) or reject as 403 (false). */
    public boolean isNotFound() {
        return notFound;
    }
}
