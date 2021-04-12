package com.github.raphcal.mailbank;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 *
 * @author Raphaël Calabro (ddaeke-github at yahoo.fr)
 */
@Data
@AllArgsConstructor
public class Mail {
    private String from;
    private List<String> to;
    private List<String> cc;
    private List<String> bcc;
    private Map<String, String> headers;
    private String content;
}
