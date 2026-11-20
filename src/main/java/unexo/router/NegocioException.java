package unexo.router;

/** Regla de negocio incumplida. El mensaje se devuelve al remitente como "Error: ...". */
public class NegocioException extends Exception {
    private static final long serialVersionUID = 1L;

    public NegocioException(String mensaje) {
        super(mensaje);
    }
}
