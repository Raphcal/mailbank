package com.github.raphcal.mailbank;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Raphaël Calabro (ddaeke-github at yahoo.fr)
 */
@Slf4j
public class MailBuilder {
    private static final String LINE_ENDING = "\r\n";
    private static final String CONTENT_TRANSFER_ENCODING_HEADER = "Content-Transfer-Encoding";
    private static final String QUOTED_PRINTABLE_CONTENT_TRANSFER_ENCODING = "quoted-printable";

    private Status status = Status.INITIAL;
    private final ByteArrayOutputStream dataBuilder = new ByteArrayOutputStream();
    private String response;

    private final String hostName;
    private String client;
    private String from;
    private List<String> to = new ArrayList<>();
    private Map<String, String> headers = new LinkedHashMap<>();

    private Charset charset = StandardCharsets.US_ASCII;

    public MailBuilder(InetSocketAddress endpoint) {
        hostName = endpoint.getHostName();
        response = "220 " + hostName + " SMTP Ready" + LINE_ENDING;
    }

    public void feedBytes(ByteBuffer buffer) throws IOException {
        String line = readLine(buffer);
        while (line != null) {
            log.trace("< " + line);
            if (status == Status.HEADERS) {
                if (line.isEmpty()) {
                    status = Status.BODY;
                } else if(line.contains(":")) {
                    String[] headerAndValue = line.split(": *", 2);
                    headers.put(headerAndValue[0], headerAndValue[1]);

                    Pattern charsetPattern = Pattern.compile("charset=([^;]+)");
                    Matcher matcher;
                    if ("Content-Type".equals(headerAndValue[0]) && (matcher = charsetPattern.matcher(headerAndValue[1])).find()) {
                        charset = Charset.forName(matcher.group(1));
                    }
                } else {
                    dataBuilder.write(line.getBytes(charset));
                    dataBuilder.write('\r');
                    dataBuilder.write('\n');
                }
            } else if (status != Status.BODY) {
                final String[] args = line.split("[ :]");

                final Command command = Command.valueOf(args[0].toUpperCase());

                if (command == Command.QUIT) {
                    response = "221 Closing connection" + LINE_ENDING;
                    status = Status.DONE;
                    return;
                }

                if (!status.getExpected().contains(command)) {
                    response = "500-" + status + ", expected one of " + status.getExpected() + ", but received: " + command + LINE_ENDING
                            + "221 Closing connection" + LINE_ENDING;
                    status = Status.DONE;
                    return;
                }

                switch (status) {
                    case INITIAL:
                        client = args[1];
                        response = "250-" + hostName + " Hello " + client + LINE_ENDING
                                + "250 AUTH PLAIN" + LINE_ENDING;
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
                                response = "250 Sender OK" + LINE_ENDING;
                                break;
                            case RCPT:
                                to.add(Arrays.stream(args)
                                        .skip(2)
                                        .collect(Collectors.joining(" ")));
                                response = "250 Recipient OK" + LINE_ENDING;
                                break;
                            case DATA:
                                status = Status.HEADERS;
                                response = "354 Enter mail, end with '.' on a line by itself" + LINE_ENDING;
                                break;
                            default:
                                throw new IllegalArgumentException("Bad command: " + line);
                        }
                        break;
                }
            } else {
                if (".".equals(line)) {
                    status = Status.DONE;
                    response = "250 Data OK";
                } else {
                    dataBuilder.write(line.getBytes(charset));
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
        String data = dataBuilder.toString();
        if (QUOTED_PRINTABLE_CONTENT_TRANSFER_ENCODING.equalsIgnoreCase(headers.get(CONTENT_TRANSFER_ENCODING_HEADER))) {
            Pattern specialChars = Pattern.compile("(?:=[A-Za-z0-9]{2})+");
            Matcher matcher = specialChars.matcher(data);
            while (matcher.find()) {
                final int[] integers = Arrays.stream(matcher.group().substring(1).split("="))
                        .mapToInt(hex -> Integer.parseInt(hex, 16))
                        .toArray();
                final byte[] bytes = new byte[integers.length];
                for (int index = 0; index < integers.length; index++) {
                    bytes[index] = (byte)integers[index];
                }
                try {
                    final String decodedValue = charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                    data = data.replace(matcher.group(), decodedValue);
                    matcher = specialChars.matcher(data);
                } catch (CharacterCodingException ex) {
                    log.error("Unable to decode bytes " + Arrays.toString(bytes) + " using charset " + charset.displayName(), ex);
                }
            }

            specialChars = Pattern.compile("=([A-Za-z0-9]{2})");
            matcher = specialChars.matcher(data);
            while (matcher.find()) {
                data = data.replace(matcher.group(), "" + ((char)Integer.parseInt(matcher.group(1), 16)));
                matcher = specialChars.matcher(data);
            }
            data = data.replace("=\n", "").replace("=\r\n", "");
        }
        
        return new Mail(from, to, null, null, headers, data);
    }

    private String readLine(ByteBuffer buffer) {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        while (buffer.hasRemaining()) {
            char c = (char) buffer.get();
            if (c != '\r' && c != '\n') {
                outputStream.write(c);
            } else if (c == '\n') {
                return new String(outputStream.toByteArray(), charset);
            }
        }
        return null;
    }

    @Getter
    private static enum Status {
        INITIAL(Command.HELO, Command.EHLO, Command.QUIT),
        REQUIRE_AUTH(Command.AUTH, Command.QUIT),
        ENVELOPE(Command.MAIL, Command.RCPT, Command.DATA, Command.QUIT),
        HEADERS,
        BODY,
        WAITING_FOR_QUIT(Command.QUIT),
        DONE;

        private final Set<Command> expected;

        private Status(Command... expected) {
            this.expected = expected.length > 0
                    ? EnumSet.copyOf(Arrays.asList(expected))
                    : EnumSet.noneOf(Command.class);
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
