
import java.util.Properties;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;


/**
 *
 * @author Raphaël Calabro (ddaeke-github at yahoo.fr)
 */
public class EchoClient {
    public static void main(String[] args) throws Exception {
        final String from = "raphael@test.fr";

        final Properties sessionProperties = new Properties();
        sessionProperties.setProperty("mail.transport.protocol", "smtp");
        sessionProperties.setProperty("mail.smtp.from", from);
        sessionProperties.setProperty("mail.smtp.port", "2525");
        sessionProperties.setProperty("mail.smtp.host", "localhost");
        sessionProperties.setProperty("mail.smtp.charset", "UTF-8");

        final Session session = Session.getInstance(sessionProperties);
        final Transport transport = session.getTransport();

        transport.connect();

        final MimeMessage message = new MimeMessage(session);

        message.setFrom(new InternetAddress(from, false));
        message.setRecipients(Message.RecipientType.TO, "hello@world.com");

        message.setSubject("Hello World !");

        message.setText("This is the mail body.");

        message.saveChanges();
        transport.sendMessage(message, message.getAllRecipients());
    }
}
