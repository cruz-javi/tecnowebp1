package unexo.router;

import unexo.mail.MailMessage;
import unexo.parser.CommandCatalog;

import java.util.List;

/** Datos de la solicitud en curso: remitente, usuario autenticado, parámetros validados y adjuntos. */
public final class Contexto {

    private final String remitente;
    private final Usuario usuario;
    private final List<String> params;
    private final List<MailMessage.Adjunto> adjuntos;

    /** Usuario registrado (null si el remitente no está registrado). */
    public record Usuario(int id, String ci, String correo, int idPerfil, String perfil, Integer idCliente) {
    }

    public Contexto(String remitente, Usuario usuario, List<String> params, List<MailMessage.Adjunto> adjuntos) {
        this.remitente = remitente;
        this.usuario = usuario;
        this.params = params;
        this.adjuntos = adjuntos;
    }

    public String remitente() {
        return remitente;
    }

    public Usuario usuario() {
        return usuario;
    }

    public String p(int i) {
        return params.get(i);
    }

    public Integer pInt(int i) {
        String v = params.get(i);
        return v == null ? null : Integer.valueOf(v);
    }

    public java.math.BigDecimal pDec(int i) {
        return new java.math.BigDecimal(params.get(i));
    }

    public java.sql.Date pFecha(int i) {
        return java.sql.Date.valueOf(params.get(i));
    }

    public java.sql.Time pHora(int i) {
        return java.sql.Time.valueOf(params.get(i) + ":00");
    }

    public List<MailMessage.Adjunto> adjuntos() {
        return adjuntos;
    }

    public boolean esPropietario() {
        return usuario != null && CommandCatalog.P.equals(usuario.perfil());
    }

    /** Un cliente solo puede operar sobre su propio CI. */
    public void exigirPropio(String ci) throws NegocioException {
        if (!esPropietario() && (usuario == null || !usuario.ci().equalsIgnoreCase(ci))) {
            throw new NegocioException("solo puede consultar o modificar sus propios datos (CI " + (usuario == null ? "-" : usuario.ci()) + ").");
        }
    }
}
