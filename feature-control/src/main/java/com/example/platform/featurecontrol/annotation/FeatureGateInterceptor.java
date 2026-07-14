package com.example.platform.featurecontrol.annotation;

import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.context.JwtFeatureContextFactory;
import com.example.platform.featurecontrol.resolver.FeatureResolver;
import io.micronaut.aop.InterceptorBean;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.security.authentication.Authentication;

/**
 * Enforces {@link FeatureGate}: resolves the annotated flag for the current caller (JWT via
 * {@code ServerRequestContext}) and, when off, throws {@link FeatureDisabledException} (mapped to
 * 404/403 by {@link FeatureDisabledExceptionHandler}) instead of proceeding. Only active when
 * micronaut-security is on the classpath.
 */
@InterceptorBean(FeatureGate.class)
@Requires(classes = Authentication.class)
public class FeatureGateInterceptor implements MethodInterceptor<Object, Object> {

    private final FeatureResolver resolver;

    public FeatureGateInterceptor(FeatureResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        AnnotationValue<FeatureGate> annotation = context.getAnnotation(FeatureGate.class);
        if (annotation == null) {
            return context.proceed();
        }
        String flag = annotation.stringValue().orElse(null);
        if (flag == null || flag.isBlank()) {
            return context.proceed();
        }
        boolean notFound = annotation.booleanValue("notFound").orElse(true);
        if (!resolver.isEnabled(flag, currentContext())) {
            throw new FeatureDisabledException(flag, notFound);
        }
        return context.proceed();
    }

    private FeatureContext currentContext() {
        Authentication auth = ServerRequestContext.currentRequest()
                .flatMap(r -> r.getUserPrincipal(Authentication.class))
                .orElse(null);
        return JwtFeatureContextFactory.from(auth, null);
    }
}
