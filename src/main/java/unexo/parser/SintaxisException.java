package unexo.parser;

/** Error de sintaxis o de validación de un comando. Su mensaje se envía al remitente. */
public class SintaxisException extends Exception {
    private static final long serialVersionUID = 1L;

    public SintaxisException(String mensaje) {
        super(mensaje);
    }
}
