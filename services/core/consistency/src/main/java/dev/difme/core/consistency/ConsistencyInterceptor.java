package dev.difme.core.consistency;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/** CDI interceptor behind @ConsistencyProfile (PDF §5.3 consistency-router). Uses Java 25 scoped values. */
@ConsistencyProfile(Consistency.EVENTUAL)
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 100)
public class ConsistencyInterceptor {

    @AroundInvoke
    Object route(InvocationContext ctx) throws Exception {
        ConsistencyProfile p = ctx.getMethod().getAnnotation(ConsistencyProfile.class);
        if (p == null) p = ctx.getMethod().getDeclaringClass().getAnnotation(ConsistencyProfile.class);
        Consistency c = p == null ? Consistency.EVENTUAL : p.value();
        return ConsistencyContext.runWith(c, ctx::proceed);
    }
}
