package org.pfj.resource;

import org.pfj.lang.Cause;
import org.pfj.lang.Functions.FN1;
import org.pfj.lang.Result;

import static org.pfj.lang.Result.lift;

/**
 * The container used by {@link AsyncResource} to access shared resource.
 * The access to resource is available until {@link #release()} method is invoked.
 */
public interface ResourceTicket<T> {
    /**
     * Get access to the resource.
     *
     * @return the resource.
     */
    T access();

    /**
     * Release resource and enable other requesters to get access to it.
     */
    void release();

    /**
     * Convenience on-step method which performs automatic release of the ticket after applying provided transformation.
     *
     * @param transformation the transformation to perform on the resource.
     */
    default <R> Result<R> perform(FN1<? extends Cause, ? super Throwable> exceptionMapper, FN1<R, T> transformation) {
        return lift(exceptionMapper, () -> transformation.apply(access()))
            .onResultDo(this::release);
    }
}
