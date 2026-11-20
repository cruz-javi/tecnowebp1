package unexo.parser;

import java.util.List;
import java.util.Set;

/**
 * Definición de un comando: nombre, caso de uso, parámetros tipados y perfiles que pueden ejecutarlo.
 *
 * Tipos de parámetro:
 *   text, ci, int, dec, date (AAAA-MM-DD), time (HH:MM), email, pass, enum:A,B,C,
 *   opt:TIPO  (acepta "-" como vacío),  star|TIPO (acepta "*")
 */
public record CommandSpec(String nombre, String cu, String descripcion, List<Param> params,
                          Set<String> perfiles, boolean requiereAdjunto) {

    public record Param(String nombre, String tipo) {
    }

    public String firma() {
        StringBuilder sb = new StringBuilder(nombre).append("[");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('"').append(params.get(i).nombre()).append('"');
        }
        sb.append("]");
        if (requiereAdjunto) {
            sb.append(" + imagen adjunta");
        }
        return sb.toString();
    }
}
