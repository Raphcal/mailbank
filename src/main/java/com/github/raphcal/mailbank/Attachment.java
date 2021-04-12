package com.github.raphcal.mailbank;

import java.nio.ByteBuffer;
import lombok.Value;

/**
 *
 * @author Raphaël Calabro (ddaeke-github at yahoo.fr)
 */
@Value
public class Attachment {
    private static final int BUFFER_SIZE = 1024;

    private ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    private MailBuilder mailBuilder;
}
