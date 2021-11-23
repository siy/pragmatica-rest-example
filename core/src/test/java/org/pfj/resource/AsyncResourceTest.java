package org.pfj.resource;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.pfj.resource.AsyncResource.asyncResource;

class AsyncResourceTest {

    @Test
    void resourceCanBeAcquiredAndReleased() {
        var resource = asyncResource("value");
        var ticketPromise = resource.request();

        assertTrue(ticketPromise.isDone());

        ticketPromise
            .onFailure(f -> fail(f.message()))
            .onSuccess(ticket -> assertEquals("value", ticket.access()))
            .join();
    }

    @Test
    void resourceCanBeLockedAsynchronously() throws InterruptedException {
        var numRequests = 1_000_000;
        var resource = asyncResource(new AtomicInteger(0));
        var latch = new CountDownLatch(numRequests);
        var buffer = new StringBuffer(); //synchronized version is required here
        var executor = Executors.newFixedThreadPool(8);

        for (int i = 1; i <= numRequests; i++) {
            var localI = i;

            executor.submit(
                () -> resource.request()
                    .onSuccess(ticket -> testSingleTicket(ticket, localI, latch, buffer))
            );
        }
        latch.await();

        if (buffer.length() > 0) {
            fail(buffer.toString());
        }
    }

    private void testSingleTicket(ResourceTicket<AtomicInteger> ticket, int localI,
                                  CountDownLatch latch, StringBuffer buffer) {
        try {
            if (!ticket.access().compareAndSet(0, localI)) {
                var message = "0 -> " + localI + " transition failed: " + ticket.access().get() + "\n";
                buffer.append(message);
            }

            if (!ticket.access().compareAndSet(localI, 0)) {
                var message = "" + localI + " -> 0 transition failed: " + ticket.access().get() + "\n";
                buffer.append(message);
            }

            ticket.access().set(0);
        } finally {
            latch.countDown();
            ticket.release();
        }
    }
}