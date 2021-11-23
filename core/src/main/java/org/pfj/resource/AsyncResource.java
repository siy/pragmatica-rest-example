package org.pfj.resource;

import org.pfj.lang.Promise;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A resource, access to which can be obtained asynchronously.
 * Unlike traditional synchronous locks, this tool does not block execution
 * if resource is already locked. Instead, it returns an instance of {@link Promise} with
 * the {@link ResourceTicket} inside. The {@link ResourceTicket} allows direct access to the locked
 * resource and releasing resource once access to it is no longer required.
 * <p>
 * It is guaranteed to only one returned {@link Promise} will be resolved at a time, so resource
 * can be freely accessed via {@link ResourceTicket#access()} without need ot any additional locks
 * or other synchronization mechanisms. The resource is accessible until {@link ResourceTicket#release()}
 * is called. Once this method is called, no further attempts to access resource should be performed,
 * as behavior of such access is undefined.
 */
public class AsyncResource<T> {
    private final ConcurrentLinkedQueue<Promise<ResourceTicket<T>>> waitQueue = new ConcurrentLinkedQueue<>();
    private final AtomicReference<ResourceTicket<T>> lock = new AtomicReference<>();
    private final T resource;

    private AsyncResource(T resource) {
        this.resource = resource;
    }

    public static <T> AsyncResource<T> asyncResource(T resource) {
        return new AsyncResource<>(resource);
    }

    public Promise<ResourceTicket<T>> request() {
        var ticket = new ResourceTicketImpl<>(this);

        return lock.compareAndSet(null, ticket)
            ? Promise.success(ticket)
            : Promise.promise(waitQueue::add);
    }

    private void release(ResourceTicket<T> resourceTicket) {
        var next = waitQueue.poll();
        var nextTicket = next == null ? null : new ResourceTicketImpl<>(this);

        if (!lock.compareAndSet(resourceTicket, nextTicket)) {
            throw new IllegalStateException("Attempt to release resource not owned by current ticket");
        }

        if (next != null) {
            next.async(promise -> promise.succeed(nextTicket));
        }
    }

    private static record ResourceTicketImpl<T>(AsyncResource<T> resource) implements ResourceTicket<T> {
        @Override
        public T access() {
            return resource().resource;
        }

        @Override
        public void release() {
            resource().release(this);
        }
    }
}
