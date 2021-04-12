
import com.github.raphcal.mailbank.MailBank;


/**
 *
 * @author Raphaël Calabro (ddaeke-github at yahoo.fr)
 */
public class EchoServer {
    public static void main(String[] args) {
        MailBank mailBank = new MailBank(2525, System.out::println);
        mailBank.start();
    }
}
