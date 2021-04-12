package com.github.raphcal.mailbank;

/**
 *
 * @author Raphaël Calabro (ddaeke-github at yahoo.fr)
 */
public interface SmtpHandler {
    void mailReceived(Mail mail);
}
