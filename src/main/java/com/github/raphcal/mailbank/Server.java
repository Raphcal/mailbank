package com.github.raphcal.mailbank;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread gérant l'envoi et la réception de requêtes HTTP.
 *
 * @author Raphaël Calabro (ddaeke-github at yahoo.fr)
 */
@Slf4j
class Server implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Server.class);

    private static final int END_OF_CHANNEL = -1;

    private InetSocketAddress endpoint;
    private int port;

    private final SmtpHandler handler;
    private final Object runningLock;
    private final Semaphore startSemaphore;

    /**
     * Créé une nouveau serveur HTTP.
     *
     * @param servlet Objet s'occupant de configurer les réponses aux requêtes
     * reçues.
     * @param port Port où écouter les requêtes.
     * @param runningLock Objet servant de verrou d'exécution.
     * @param startLock Lock de démarrage.
     */
    public Server(SmtpHandler handler, int port, Object runningLock, Semaphore startSemaphore) {
        this.port = port;
        this.handler = handler;
        this.runningLock = runningLock;
        this.startSemaphore = startSemaphore;

        try {
            startSemaphore.acquire();
        } catch (InterruptedException ex) {
            LOGGER.error("Interrupted before acquiring start semaphore.", ex);
        }
    }

    @Override
    public void run() {
        synchronized (runningLock) {
            try (final ServerSocketChannel serverChannel = ServerSocketChannel.open(); final Selector selector = Selector.open()) {
                startServer(serverChannel, selector);

                while (!Thread.currentThread().isInterrupted()) {
                    handleIO(selector);
                }
            } catch (IOException | RuntimeException ex) {
                LOGGER.error("An unexpected error happened for server " + endpoint, ex);
            }
        }
    }

    public InetSocketAddress getEndpoint() {
        return endpoint;
    }

    private void startServer(ServerSocketChannel serverChannel, Selector selector) throws ClosedChannelException, IOException {
        serverChannel.configureBlocking(false);
        while (endpoint == null) {
            final InetSocketAddress address = new InetSocketAddress(port);
            try {
                serverChannel.socket().bind(address);
                this.endpoint = address;
            } catch (IOException e) {
                LOGGER.debug("Unable to bind to address " + address, e);
                port++;
            }
        }
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        startSemaphore.release();
    }

    private void handleIO(final Selector selector) throws IOException {
        selector.select();

        final Set<SelectionKey> keys = selector.selectedKeys();
        final Iterator<SelectionKey> keyIterator = keys.iterator();
        while (keyIterator.hasNext()) {
            final SelectionKey key = keyIterator.next();
            keyIterator.remove();

            if (key.isAcceptable()) {
                acceptClient(key, selector);
            } else if (key.isReadable()) {
                readData(key);
            } else if (key.isWritable()) {
                writeData(key);
            }
        }
    }

    private void acceptClient(final SelectionKey key, final Selector selector) throws IOException, ClosedChannelException {
        final ServerSocketChannel server = (ServerSocketChannel) key.channel();

        final SocketChannel channel = server.accept();
        channel.configureBlocking(false);

        channel.register(selector, SelectionKey.OP_WRITE, new Attachment(new MailBuilder(endpoint)));
    }

    private void readData(final SelectionKey key) throws IOException {
        final SocketChannel channel = (SocketChannel) key.channel();

        final Attachment attachment = (Attachment) key.attachment();
        final MailBuilder mailBuilder = attachment.getMailBuilder();
        final ByteBuffer buffer = attachment.getBuffer();
        int bytes = channel.read(buffer);

        if (bytes == END_OF_CHANNEL) {
            channel.close();
            key.cancel();
        } else if (bytes > 0) {
            ((Buffer) buffer).flip();
            mailBuilder.feedBytes(buffer);
            buffer.compact();
        }

        if (mailBuilder.hasResponse()) {
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private void writeData(final SelectionKey key) throws IOException {
        final Attachment attachment = (Attachment) key.attachment();
        final MailBuilder mailBuilder = attachment.getMailBuilder();
        final SocketChannel channel = (SocketChannel) key.channel();
        final String response = mailBuilder.getResponse();
        log.trace("> " + response);
        final ByteBuffer buffer = ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII));
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }

        if (mailBuilder.isDone()) {
            channel.close();
            key.cancel();
            handler.mailReceived(mailBuilder.build());
        } else {
            key.interestOps(SelectionKey.OP_READ);
        }
    }

}
