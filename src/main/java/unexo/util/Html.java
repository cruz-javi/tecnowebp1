package unexo.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Utilidades para armar tablas HTML y de texto en las respuestas. */
public final class Html {

    private Html() {
    }

    public static String esc(Object o) {
        if (o == null) {
            return "";
        }
        return o.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static String tabla(List<Map<String, Object>> filas) {
        if (filas.isEmpty()) {
            return "<p><i>Sin registros.</i></p>";
        }
        StringBuilder sb = new StringBuilder("<table border=\"1\" cellpadding=\"4\" cellspacing=\"0\" style=\"border-collapse:collapse\"><tr style=\"background:#e7e6e6\">");
        for (String col : filas.get(0).keySet()) {
            sb.append("<th>").append(esc(col)).append("</th>");
        }
        sb.append("</tr>");
        for (Map<String, Object> f : filas) {
            sb.append("<tr>");
            for (Object v : f.values()) {
                sb.append("<td>").append(esc(v)).append("</td>");
            }
            sb.append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    public static String tablaTexto(List<Map<String, Object>> filas) {
        if (filas.isEmpty()) {
            return "(sin registros)";
        }
        List<String> cols = new ArrayList<>(filas.get(0).keySet());
        int[] ancho = new int[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            ancho[i] = Math.min(40, cols.get(i).length());
        }
        for (Map<String, Object> f : filas) {
            int i = 0;
            for (Object v : f.values()) {
                ancho[i] = Math.min(40, Math.max(ancho[i], str(v).length()));
                i++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            sb.append(pad(cols.get(i), ancho[i])).append(" | ");
        }
        sb.append("\n");
        for (int w : ancho) {
            sb.append("-".repeat(w)).append("-+-");
        }
        sb.append("\n");
        for (Map<String, Object> f : filas) {
            int i = 0;
            for (Object v : f.values()) {
                sb.append(pad(str(v), ancho[i])).append(" | ");
                i++;
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Gráfico de barras horizontal compatible con clientes de correo (tablas). */
    public static String barras(String titulo, List<String> etiquetas, List<Double> valores) {
        double max = valores.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        if (max <= 0) {
            max = 1;
        }
        StringBuilder sb = new StringBuilder("<h4>").append(esc(titulo)).append("</h4><table cellpadding=\"2\">");
        for (int i = 0; i < etiquetas.size(); i++) {
            int w = (int) Math.round(300 * valores.get(i) / max);
            sb.append("<tr><td>").append(esc(etiquetas.get(i))).append("</td><td>")
                    .append("<table cellpadding=\"0\" cellspacing=\"0\"><tr><td bgcolor=\"#2e7d32\" width=\"")
                    .append(Math.max(w, 1)).append("\" height=\"14\"></td></tr></table></td><td>")
                    .append(String.format("%.2f", valores.get(i))).append("</td></tr>");
        }
        return sb.append("</table>").toString();
    }

    public static String barrasTexto(String titulo, List<String> etiquetas, List<Double> valores) {
        double max = valores.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        if (max <= 0) {
            max = 1;
        }
        StringBuilder sb = new StringBuilder(titulo).append("\n");
        for (int i = 0; i < etiquetas.size(); i++) {
            int n = (int) Math.round(30 * valores.get(i) / max);
            sb.append(pad(etiquetas.get(i), 25)).append(" ").append("#".repeat(n))
                    .append(String.format(" %.2f", valores.get(i))).append("\n");
        }
        return sb.toString();
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String pad(String s, int w) {
        if (s.length() > w) {
            return s.substring(0, w - 1) + "…";
        }
        return s + " ".repeat(w - s.length());
    }
}
