package io.github.bdd.engine;

/**
 * A request to stop an execution, which may come from any thread. The step being executed is
 * interrupted, so that one waiting for data ends at once; the steps that follow are not executed,
 * and the execution ends with the reports of what ran.
 */
public final class Cancellation {

    private boolean cancelled;
    /** The thread executing a step, {@code null} between steps. */
    private Thread stepThread;

    public synchronized void cancel() {
        cancelled = true;
        if (stepThread != null) {
            stepThread.interrupt();
        }
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /** @throws IllegalStateException if the execution was cancelled, so that the step is not executed */
    synchronized void enterStep() {
        if (cancelled) {
            throw new IllegalStateException("The execution was cancelled");
        }
        stepThread = Thread.currentThread();
    }

    /** Leaves the thread as it was before the step, whatever interrupted the step. */
    synchronized void leaveStep() {
        stepThread = null;
        if (cancelled) {
            Thread.interrupted();
        }
    }
}
