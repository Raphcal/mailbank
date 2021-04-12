package com.github.raphcal.mailbank;

import java.net.InetSocketAddress;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Raphaël Calabro (ddaeke-github at yahoo.fr)
 */
public class MailBank {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailBank.class);

    private final Server server;
    private final Thread serverThread;

    private long startTime;

    private final Object runningLock = new Object();
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final Semaphore startSemaphore = new Semaphore(1);

    public MailBank(int port, SmtpHandler servlet) {
        this.server = new Server(servlet, port, runningLock, startSemaphore);
        this.serverThread = new Thread(server);
    }

    /**
     * {@inheritDoc}
     */
    public void start() {
        if (!serverThread.isAlive()) {
            LOGGER.info("Starting server thread...");
            startTime = new Date().getTime();
            serverThread.start();
            try {
                startSemaphore.acquire();
            } catch (InterruptedException ex) {
                LOGGER.error("Server start has been interrupted.", ex);
            }
            LOGGER.info("Server listening on " + server.getEndpoint());
        } else {
            LOGGER.warn("Server is already started and listening on " + server.getEndpoint());
        }
    }

    /**
     * {@inheritDoc}
     */
    public void stop() {
        if (serverThread != null) {
            LOGGER.info("Stopping server " + server.getEndpoint() + "...");
            serverThread.interrupt();

            synchronized (runningLock) {
                LOGGER.info("Server stopped (total execution time : "
                        + TimeUnit.SECONDS.convert(new Date().getTime() - startTime, TimeUnit.MILLISECONDS)
                        + "s).");
            }
        } else {
            LOGGER.warn("Server is not started.");
        }
    }

    /**
     * {@inheritDoc}
     */
    public void stop(long delay, TimeUnit unit) {
        if (stopping.compareAndSet(false, true)) {
            LOGGER.info("Server will stop in " + delay + ' ' + unit.name().toLowerCase() + '.');

            final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
            executorService.schedule(new Runnable() {

                @Override
                public void run() {
                    stop();
                    executorService.shutdown();
                }

            }, delay, unit);
        }
    }

    /**
     * {@inheritDoc}
     */
    public InetSocketAddress getEndpoint() {
        return server.getEndpoint();
    }

}
