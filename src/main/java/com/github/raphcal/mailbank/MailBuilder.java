package com.github.raphcal.mailbank;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 *
 * @author Raphaël Calabro (ddaeke-github at yahoo.fr)
 */
public class MailBuilder {
    private static final String LINE_ENDING = "\r\n";

    private Status status = Status.INITIAL;
    private final StringBuilder lineBuilder = new StringBuilder();
    private final ByteArrayOutputStream dataBuilder = new ByteArrayOutputStream();
    private String response;

    private final String hostName;
    private String client;
    private String from;
    private List<String> to = new ArrayList<>();

    public MailBuilder(InetSocketAddress endpoint) {
        hostName = endpoint.getHostName();
        response = "220 " + hostName + " SMTP Ready" + LINE_ENDING;
    }

    public void feedBytes(ByteBuffer buffer) throws IOException {
        String line = readLine(buffer);
        while (line != null) {
            if (status != Status.BODY) {
                final String[] args = line.split(" ");

                final Command command = Command.valueOf(args[0].toUpperCase());
                if (!status.getExpected().contains(command)) {
                    throw new IllegalArgumentException(status + ", expected one of " + status.getExpected() + ", but received: " + command);
                }

                switch (status) {
                    case INITIAL:
                        client = args[1];
                        response = "250-" + hostName + " Hello " + client + LINE_ENDING;
                        status = Status.ENVELOPE;
                        break;
                    case REQUIRE_AUTH:
                        throw new UnsupportedOperationException("Not supported yet");
                    case ENVELOPE:
                        switch (command) {
                            case MAIL:
                                from = Arrays.stream(args)
                                        .skip(2)
                                        .collect(Collectors.joining(" "));
                                break;
                            case RCPT:
                                to.add(Arrays.stream(args)
                                        .skip(2)
                                        .collect(Collectors.joining(" ")));
                                break;
                            case DATA:
                                status = Status.BODY;
                                break;
                            default:
                                throw new IllegalArgumentException("Bad command: " + line);
                        }
                        break;
                    case WAITING_FOR_QUIT:
                        response = "221 Closing connection";
                        break;
                }

                status = Status.values()[status.ordinal() + 1];
            } else {
                if (".".equals(line)) {
                    status = Status.WAITING_FOR_QUIT;
                    response = "250 OK";
                } else {
                    dataBuilder.write(line.getBytes(StandardCharsets.US_ASCII));
                    dataBuilder.write('\r');
                    dataBuilder.write('\n');
                }
            }
            line = readLine(buffer);
        }
    }

    public boolean hasResponse() {
        return response != null;
    }

    public String getResponse() {
        final String value = this.response;
        this.response = null;
        return value;
    }

    public boolean isDone() {
        return status == Status.DONE;
    }

    public Mail build() {
        return new Mail(from, to, null, null, null, dataBuilder.toString());
    }

    private String readLine(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            char c = (char) buffer.get();
            if (c != '\r' && c != '\n') {
                lineBuilder.append(c);
            } else if (c == '\n') {
                final String line = lineBuilder.toString();
                lineBuilder.setLength(0);
                return line;
            }
        }
        return null;
    }

    @Getter
    private static enum Status {
        INITIAL(Command.HELO, Command.EHLO, Command.QUIT),
        REQUIRE_AUTH(Command.AUTH, Command.QUIT),
        ENVELOPE(Command.MAIL, Command.RCPT, Command.DATA, Command.QUIT),
        BODY,
        WAITING_FOR_QUIT(Command.QUIT),
        DONE;

        private final Set<Command> expected;

        private Status(Command... expected) {
            this.expected = EnumSet.copyOf(Arrays.asList(expected));
        }
    }

    private static enum Command {
        // Start with: 220 hostname SMTP Ready OR 220 hostname ESMTP Ready

        // If sent "SMTP Ready", waiting for HELO
        HELO, // HELO client
        // 250-hostname
        // 250-PIPELINING
        // 250 8BITMIME   

        // If sent "ESMTP Ready", waiting for EHLO
        EHLO, // EHLO client.example.com
        // 250-hostname Hello client.example.com
        // 250-AUTH GSSAPI DIGEST-MD5
        // 250-ENHANCEDSTATUSCODES
        // 250 STARTTLS

        AUTH, // AUTH PLAIN dGVzdAB0ZXN0ADEyMzQ=
        // 235 2.7.0 Authentication successful

        MAIL, // MAIL FROM: <auteur@yyyy.yyyy>
        // 250 Sender ok


        RCPT, // RCPT TO: <destinataire@----.---->
        // 250 Recipient ok.

        DATA,
        // 354 Enter mail, end with "." on a line by itself
        // Subject: Test
        
        // Corps du texte
        // .
        // 250 Ok

        QUIT
        // 221 Closing connection
        ;
    }
}
