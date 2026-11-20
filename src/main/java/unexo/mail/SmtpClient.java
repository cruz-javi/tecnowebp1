package unexo.mail;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * Cliente SMTP (RFC 5321) sobre sockets. Envía la respuesta como multipart/alternative
 * (texto plano + HTML) en UTF-8, con el asunto codificado según RFC 2047.
 */
public class SmtpClient {

    private final String host;
    private final int puerto;
    private final String usuario;
    private final String password;

    public SmtpClient(String host, int puerto, String usuario, String password) {
        this.host = host;
        this.puerto = puerto;
        this.usuario = usuario;
        this.password = password;
    }

    public void enviar(String from, String to, String asunto, String texto, String html) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, puerto), 15_000);
            s.setSoTimeout(30_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.US_ASCII));

            esperar(in, "220");
            String dominio = from.substring(from.indexOf('@') + 1);
            orden(out, in, "EHLO " + dominio, "250");
            if (usuario != null && !usuario.isEmpty()) {
                orden(out, in, "AUTH LOGIN", "334");
                orden(out, in, b64(usuario), "334");
                orden(out, in, b64(password), "235");
            }
            orden(out, in, "MAIL FROM:<" + from + ">", "250");
            orden(out, in, "RCPT TO:<" + to + ">", "250");
            orden(out, in, "DATA", "354");
            out.write(mensaje(from, to, asunto, texto, html));
            out.write("\r\n.\r\n");
            out.flush();
            esperar(in, "250");
            orden(out, in, "QUIT", "221");
        }
    }

    private static String mensaje(String from, String to, String asunto, String texto, String html) {
        String limite = "unexo-" + UUID.randomUUID();
        String fecha = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH));
        StringBuilder sb = new StringBuilder();
        sb.append("From: UNEXO <").append(from).append(">\r\n")
          .append("To: <").append(to).append(">\r\n")
          .append("Subject: ").append(codificarAsunto(asunto)).append("\r\n")
          .append("Date: ").append(fecha).append("\r\n")
          .append("Message-ID: <").append(UUID.randomUUID()).append("@").append(from.substring(from.indexOf('@') + 1)).append(">\r\n")
          .append("Auto-Submitted: auto-replied\r\n")
          .append("MIME-Version: 1.0\r\n")
          .append("Content-Type: multipart/alternative; boundary=\"").append(limite).append("\"\r\n\r\n")
          .append("--").append(limite).append("\r\n")
          .append("Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
          .append(b64Lineas(texto)).append("\r\n")
          .append("--").append(limite).append("\r\n")
          .append("Content-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
          .append(b64Lineas(html)).append("\r\n")
          .append("--").append(limite).append("--");
        return sb.toString();
    }

    static String codificarAsunto(String asunto) {
        boolean ascii = asunto.chars().allMatch(c -> c >= 32 && c < 127);
        if (ascii) {
            return asunto;
        }
        // Palabras codificadas de máximo 75 caracteres, separadas por pliegue de línea.
        StringBuilder sb = new StringBuilder();
        StringBuilder trozo = new StringBuilder();
        for (int i = 0; i < asunto.length(); ) {
            int cp = asunto.codePointAt(i);
            String c = new String(Character.toChars(cp));
            if ((trozo + c).getBytes(StandardCharsets.UTF_8).length > 40) {
                sb.append(sb.length() == 0 ? "" : "\r\n ").append("=?UTF-8?B?").append(b64(trozo.toString())).append("?=");
                trozo.setLength(0);
            }
            trozo.append(c);
            i += Character.charCount(cp);
        }
        if (trozo.length() > 0) {
            sb.append(sb.length() == 0 ? "" : "\r\n ").append("=?UTF-8?B?").append(b64(trozo.toString())).append("?=");
        }
        return sb.toString();
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64Lineas(String s) {
        return Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void orden(BufferedWriter out, BufferedReader in, String linea, String codigo) throws IOException {
        out.write(linea + "\r\n");
        out.flush();
        esperar(in, codigo);
    }

    /** Lee la respuesta (incluidas las multilínea "250-...") y verifica el código. */
    private static void esperar(BufferedReader in, String codigo) throws IOException {
        String l;
        do {
            l = in.readLine();
            if (l == null) {
                throw new IOException("SMTP: conexión cerrada esperando " + codigo);
            }
        } while (l.length() > 3 && l.charAt(3) == '-');
        if (!l.startsWith(codigo)) {
            throw new IOException("SMTP: se esperaba " + codigo + " y se recibió \"" + l + "\"");
        }
    }
}
