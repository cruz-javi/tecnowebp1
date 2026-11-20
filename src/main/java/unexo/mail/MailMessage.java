package unexo.mail;

import java.util.List;

/** Correo recibido ya decodificado: remitente, asunto (UTF-8) y adjuntos. */
public record MailMessage(String from, String subject, List<Adjunto> adjuntos) {

    public record Adjunto(String nombre, String contentType, byte[] datos) {
    }
}
