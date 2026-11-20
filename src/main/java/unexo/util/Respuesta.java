package unexo.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Respuesta de un comando: texto plano + HTML.
 * Toda respuesta comienza con "Éxito:" o "Error:".
 */
public final class Respuesta {

    private final boolean exito;
    private final String mensaje;
    private final StringBuilder texto = new StringBuilder();
    private final StringBuilder html = new StringBuilder();

    private Respuesta(boolean exito, String mensaje) {
        this.exito = exito;
        this.mensaje = (exito ? "Éxito: " : "Error: ") + mensaje;
    }

    public static Respuesta ok(String mensaje) {
        return new Respuesta(true, mensaje);
    }

    public static Respuesta error(String mensaje) {
        return new Respuesta(false, mensaje);
    }

    /** Respuesta de listado con tabla. */
    public static Respuesta tabla(String titulo, List<Map<String, Object>> filas) {
        Respuesta r = ok(titulo + " (" + filas.size() + " registro" + (filas.size() == 1 ? "" : "s") + ")");
        r.agregarTabla(filas);
        return r;
    }

    public Respuesta agregarTexto(String linea) {
        texto.append(linea).append("\n");
        html.append("<p>").append(Html.esc(linea)).append("</p>");
        return this;
    }

    public Respuesta agregarTabla(List<Map<String, Object>> filas) {
        texto.append(Html.tablaTexto(filas)).append("\n");
        html.append(Html.tabla(filas));
        return this;
    }

    public Respuesta agregarHtml(String fragmento, String equivalenteTexto) {
        html.append(fragmento);
        texto.append(equivalenteTexto).append("\n");
        return this;
    }

    public boolean isExito() {
        return exito;
    }

    public String getMensaje() {
        return mensaje;
    }

    public String getTexto() {
        return mensaje + "\n\n" + texto + "\n--\nUNEXO · Sistema Email-Driven (grupo06sa)\nEnvíe HELP[\"*\"] para ver sus comandos.";
    }

    public String getHtml() {
        String color = exito ? "#1b5e20" : "#b71c1c";
        return "<html><body style=\"font-family:Arial,sans-serif;font-size:14px\">"
                + "<h3 style=\"color:" + color + "\">" + Html.esc(mensaje) + "</h3>"
                + html
                + "<hr><small>UNEXO · Sistema Email-Driven (grupo06sa). Envíe HELP[\"*\"] para ver sus comandos.</small>"
                + "</body></html>";
    }

    public static List<String> lineas(String... l) {
        return new ArrayList<>(List.of(l));
    }
}
