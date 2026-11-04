package io.github.illuseahashmap.workflow.shared.context;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Scoped monotonic budget shared by an execution and its synchronous infrastructure calls. */
public final class ExecutionDeadline implements AutoCloseable {
    private static final ThreadLocal<ExecutionDeadline> CURRENT = new ThreadLocal<>();
    private final ExecutionDeadline previous;
    private final LongSupplier ticker;
    private final long started;
    private final long budgetNanos;

    private ExecutionDeadline(Duration budget, LongSupplier ticker) {
        this.previous = CURRENT.get();
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        if (budget.isNegative() || budget.isZero()) { throw new Exceeded(); }
        Duration bounded = previous == null ? budget : minimum(budget, previous.remaining());
        this.budgetNanos = bounded.toNanos();
        this.started = ticker.getAsLong();
        CURRENT.set(this);
    }

    public static ExecutionDeadline open(Duration budget, LongSupplier ticker) {
        return new ExecutionDeadline(Objects.requireNonNull(budget, "budget"), ticker);
    }

    public static Duration remainingOrNull() {
        var current = CURRENT.get();
        return current == null ? null : current.remaining();
    }

    public static boolean expired() {
        var current = CURRENT.get();
        return current != null && current.budgetNanos - (current.ticker.getAsLong() - current.started) <= 0;
    }

    public Duration remaining() {
        long nanos = budgetNanos - (ticker.getAsLong() - started);
        if (nanos <= 0) { throw new Exceeded(); }
        return Duration.ofNanos(nanos);
    }

    public static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public static final class Exceeded extends RuntimeException {
        public Exceeded() { super("Execution deadline has been exceeded"); }
    }
}
