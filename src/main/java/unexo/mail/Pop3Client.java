package unexo.mail;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Cliente POP3 (RFC 1939) sobre sockets: USER, PASS, STAT, LIST, RETR, DELE, NOOP, QUIT.
 * Se lee en ISO-8859-1 para conservar los bytes; la decodificación de charset la hace MimeParser.
 */
public class Pop3Client implements Closeable {

    private final String host;
    private final int puerto;
    private final boolean ssl;
    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;

    public Pop3Client(String host, int puerto, boolean ssl) {
        this.host = host;
        this.puerto = puerto;
        this.ssl = ssl;
    }

    public void conectar(String usuario, String password) throws IOException {
        socket = ssl ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(host, puerto), 15_000);
        socket.setSoTimeout(30_000);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1));
        ok(in.readLine(), "saludo");
        comando("USER " + usuario);
        String r = enviar("PASS " + password);
        if (r == null || !r.startsWith("+OK")) {
            throw new IOException("POP3: autenticación rechazada");
        }
    }

    /** Números de los mensajes pendientes (LIST). */
    public List<Integer> listar() throws IOException {
        comando("LIST");
        List<Integer> ids = new ArrayList<>();
        for (String linea : multilinea()) {
            String[] p = linea.trim().split("\\s+");
            if (p.length >= 1 && !p[0].isEmpty()) {
                ids.add(Integer.parseInt(p[0]));
            }
        }
        return ids;
    }

    /** Mensaje completo (RETR n) como texto ISO-8859-1 con CRLF. */
    public String recuperar(int n) throws IOException {
        comando("RETR " + n);
        StringBuilder sb = new StringBuilder();
        for (String linea : multilinea()) {
            sb.append(linea).append("\r\n");
        }
        return sb.toString();
    }

    public void eliminar(int n) throws IOException {
        comando("DELE " + n);
    }

    public void noop() throws IOException {
        comando("NOOP");
    }

    public void quit() throws IOException {
        if (out != null) {
            enviar("QUIT");
        }
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // cierre silencioso
        }
    }

    // ---------- protocolo ----------

    private String enviar(String linea) throws IOException {
        out.write(linea + "\r\n");
        out.flush();
        return in.readLine();
    }

    private void comando(String linea) throws IOException {
        ok(enviar(linea), linea.startsWith("PASS") ? "PASS" : linea);
    }

    private static void ok(String respuesta, String cmd) throws IOException {
        if (respuesta == null || !respuesta.startsWith("+OK")) {
            throw new IOException("POP3 " + cmd + ": " + respuesta);
        }
    }

    /** Lee una respuesta multilínea hasta ".", deshaciendo el dot-stuffing. */
    private List<String> multilinea() throws IOException {
        List<String> lineas = new ArrayList<>();
        String l;
        while ((l = in.readLine()) != null) {
            if (".".equals(l)) {
                return lineas;
            }
            lineas.add(l.startsWith("..") ? l.substring(1) : l);
        }
        throw new IOException("POP3: conexión cerrada antes del fin de la respuesta");
    }
}
