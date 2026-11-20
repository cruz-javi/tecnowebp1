package unexo.mail;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodificador MIME mínimo:
 *  - desdobla cabeceras (RFC 5322),
 *  - decodifica encoded-words =?charset?B|Q?...?= del asunto (RFC 2047),
 *  - recorre partes multipart y extrae los adjuntos en base64.
 * El mensaje crudo llega como String ISO-8859-1 (un char por byte).
 */
public final class MimeParser {

    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=");
    private static final Pattern EMAIL = Pattern.compile("<([^>]+)>");

    private MimeParser() {
    }

    public static MailMessage parsear(String crudo) {
        Parte p = parte(crudo);
        String from = direccion(p.cabeceras.getOrDefault("from", ""));
        String subject = decodificarCabecera(p.cabeceras.getOrDefault("subject", ""));
        List<MailMessage.Adjunto> adjuntos = new ArrayList<>();
        recolectarAdjuntos(p, adjuntos, 0);
        return new MailMessage(from, subject, adjuntos);
    }

    /** Cabeceras de un mensaje crudo (en minúsculas), útil para detectar respuestas automáticas. */
    public static Map<String, String> cabeceras(String crudo) {
        return parte(crudo).cabeceras;
    }

    // ---------- estructura ----------

    private static final class Parte {
        final Map<String, String> cabeceras;
        final String cuerpo;

        Parte(Map<String, String> cabeceras, String cuerpo) {
            this.cabeceras = cabeceras;
            this.cuerpo = cuerpo;
        }
    }

    private static Parte parte(String crudo) {
        String texto = crudo.replace("\r\n", "\n");
        int fin = texto.indexOf("\n\n");
        String cab = fin < 0 ? texto : texto.substring(0, fin);
        String cuerpo = fin < 0 ? "" : texto.substring(fin + 2);
        Map<String, String> headers = new LinkedHashMap<>();
        String actual = null;
        for (String linea : cab.split("\n")) {
            if ((linea.startsWith(" ") || linea.startsWith("\t")) && actual != null) {
                headers.put(actual, headers.get(actual) + " " + linea.trim());
            } else {
                int k = linea.indexOf(':');
                if (k > 0) {
                    actual = linea.substring(0, k).trim().toLowerCase(Locale.ROOT);
                    headers.putIfAbsent(actual, linea.substring(k + 1).trim());
                }
            }
        }
        return new Parte(headers, cuerpo);
    }

    private static void recolectarAdjuntos(Parte p, List<MailMessage.Adjunto> out, int nivel) {
        if (nivel > 5) {
            return;
        }
        String ct = p.cabeceras.getOrDefault("content-type", "text/plain");
        String ctLower = ct.toLowerCase(Locale.ROOT);
        if (ctLower.startsWith("multipart/")) {
            String boundary = parametro(ct, "boundary");
            if (boundary == null) {
                return;
            }
            String sep = "--" + boundary;
            String[] trozos = p.cuerpo.split(Pattern.quote(sep));
            for (int i = 1; i < trozos.length; i++) {
                String t = trozos[i];
                if (t.startsWith("--")) {
                    break;
                }
                recolectarAdjuntos(parte(t.startsWith("\n") ? t.substring(1) : t), out, nivel + 1);
            }
            return;
        }
        String disp = p.cabeceras.getOrDefault("content-disposition", "");
        String nombre = parametro(disp, "filename");
        if (nombre == null) {
            nombre = parametro(ct, "name");
        }
        boolean esAdjunto = disp.toLowerCase(Locale.ROOT).startsWith("attachment") || ctLower.startsWith("image/") || nombre != null;
        if (!esAdjunto || nivel == 0 && !ctLower.startsWith("image/")) {
            return;
        }
        String cte = p.cabeceras.getOrDefault("content-transfer-encoding", "").toLowerCase(Locale.ROOT);
        byte[] datos;
        if (cte.contains("base64")) {
            datos = Base64.getMimeDecoder().decode(p.cuerpo.replaceAll("[^A-Za-z0-9+/=]", ""));
        } else {
            datos = p.cuerpo.getBytes(StandardCharsets.ISO_8859_1);
        }
        out.add(new MailMessage.Adjunto(nombre == null ? null : decodificarCabecera(nombre), ct.split(";")[0].trim(), datos));
    }

    // ---------- cabeceras ----------

    static String direccion(String from) {
        Matcher m = EMAIL.matcher(from);
        String d = m.find() ? m.group(1) : from;
        return d.trim().toLowerCase(Locale.ROOT);
    }

    /** Decodifica RFC 2047 y también asuntos UTF-8 enviados "en crudo" (8 bits). */
    public static String decodificarCabecera(String valor) {
        if (valor == null) {
            return "";
        }
        String v = utf8SiCorresponde(valor);
        // Palabras codificadas adyacentes separadas solo por espacios se unen sin espacio.
        v = v.replaceAll("(\\?=)\\s+(=\\?)", "$1$2");
        Matcher m = ENCODED_WORD.matcher(v);
        StringBuilder sb = new StringBuilder();
        int ultimo = 0;
        while (m.find()) {
            sb.append(v, ultimo, m.start());
            sb.append(decodificarPalabra(m.group(1), m.group(2), m.group(3)));
            ultimo = m.end();
        }
        sb.append(v.substring(ultimo));
        return sb.toString().trim();
    }

    private static String decodificarPalabra(String charset, String modo, String texto) {
        Charset cs;
        try {
            cs = Charset.forName(charset.split("\\*")[0]);
        } catch (Exception e) {
            cs = StandardCharsets.UTF_8;
        }
        byte[] bytes;
        if ("B".equalsIgnoreCase(modo)) {
            bytes = Base64.getDecoder().decode(texto);
        } else {
            bytes = quotedPrintable(texto.replace('_', ' '));
        }
        return new String(bytes, cs);
    }

    private static byte[] quotedPrintable(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' && i + 3 <= s.length()) {
                try {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (RuntimeException ignored) {
                    // '=' literal
                }
            }
            out.write((byte) c);
        }
        return out.toByteArray();
    }

    private static String utf8SiCorresponde(String latin1) {
        boolean alto = false;
        for (int i = 0; i < latin1.length(); i++) {
            if (latin1.charAt(i) > 127) {
                alto = true;
                break;
            }
        }
        if (!alto) {
            return latin1;
        }
        byte[] b = latin1.getBytes(StandardCharsets.ISO_8859_1);
        String utf = new String(b, StandardCharsets.UTF_8);
        return utf.contains("\uFFFD") ? latin1 : utf;
    }

    static String parametro(String cabecera, String nombre) {
        if (cabecera == null) {
            return null;
        }
        Matcher m = Pattern.compile("(?i)" + Pattern.quote(nombre) + "\\*?=\\s*(\"([^\"]*)\"|([^;\\s]+))").matcher(cabecera);
        if (!m.find()) {
            return null;
        }
        return m.group(2) != null ? m.group(2) : m.group(3);
    }
}
