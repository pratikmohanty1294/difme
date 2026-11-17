package dev.difme.core.consistency;

/** The consistency level of the operation currently executing on this (virtual) thread. */
public final class ConsistencyContext {
    private static final ScopedValue<Consistency> CURRENT = ScopedValue.newInstance();

    private ConsistencyContext() {}

    public static Consistency current() {
        return CURRENT.isBound() ? CURRENT.get() : Consistency.EVENTUAL;
    }

    public static Backend backend() {
        return RoutingMatrix.route(current());
    }

    static <T> T runWith(Consistency c, ScopedValue.CallableOp<T, Exception> op) throws Exception {
        return ScopedValue.where(CURRENT, c).call(op);
    }
}
