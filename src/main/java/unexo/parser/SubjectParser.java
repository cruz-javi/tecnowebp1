package unexo.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Analizador léxico del asunto. Gramática:
 *
 *   comando    := espacios NOMBRE espacios '[' espacios [ param (espacios ',' espacios param)* ] espacios ']' espacios
 *   NOMBRE     := letra{3,10}
 *   param      := '"' ( car | '\"' | '\\' )* '"'  |  token_sin_comillas
 *
 * Soporta espacios adicionales, mayúsculas/minúsculas en el nombre, comillas escapadas (\")
 * y texto UTF-8 (el asunto llega ya decodificado desde MIME).
 */
public final class SubjectParser {

    private final String s;
    private int i;

    private SubjectParser(String s) {
        this.s = s;
    }

    public static Comando parsear(String asunto) throws SintaxisException {
        if (asunto == null || asunto.isBlank()) {
            throw new SintaxisException("el asunto está vacío. Use el formato COMANDO[\"p1\",\"p2\",...] o HELP[\"*\"].");
        }
        return new SubjectParser(asunto.strip()).comando();
    }

    private Comando comando() throws SintaxisException {
        espacios();
        int inicio = i;
        while (i < s.length() && Character.isLetter(s.charAt(i))) {
            i++;
        }
        String nombre = s.substring(inicio, i).toUpperCase(Locale.ROOT);
        if (nombre.length() < 3 || nombre.length() > 10) {
            throw new SintaxisException("nombre de comando inválido \"" + recorte(s) + "\". Formato: COMANDO[\"p1\",...]");
        }
        espacios();
        if (!consumir('[')) {
            throw new SintaxisException("se esperaba \"[\" después de " + nombre + ".");
        }
        List<String> params = new ArrayList<>();
        espacios();
        if (consumir(']')) {
            return fin(nombre, params);
        }
        while (true) {
            espacios();
            params.add(parametro(params.size() + 1));
            espacios();
            if (consumir(',')) {
                continue;
            }
            if (consumir(']')) {
                return fin(nombre, params);
            }
            if (i >= s.length()) {
                throw new SintaxisException("falta el corchete de cierre \"]\".");
            }
            throw new SintaxisException("carácter inesperado '" + s.charAt(i) + "' en la posición " + (i + 1)
                    + ". Separe los parámetros con comas.");
        }
    }

    private Comando fin(String nombre, List<String> params) throws SintaxisException {
        espacios();
        if (i < s.length()) {
            throw new SintaxisException("texto adicional después de \"]\": \"" + recorte(s.substring(i)) + "\".");
        }
        return new Comando(nombre, params);
    }

    private String parametro(int n) throws SintaxisException {
        if (i >= s.length()) {
            throw new SintaxisException("falta el corchete de cierre \"]\".");
        }
        if (consumir('"')) {
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '\\' && i < s.length() && (s.charAt(i) == '"' || s.charAt(i) == '\\')) {
                    sb.append(s.charAt(i++));
                } else if (c == '"') {
                    return sb.toString().strip();
                } else {
                    sb.append(c);
                }
            }
            throw new SintaxisException("el parámetro " + n + " no cierra sus comillas.");
        }
        int inicio = i;
        while (i < s.length() && s.charAt(i) != ',' && s.charAt(i) != ']') {
            if (s.charAt(i) == '"') {
                throw new SintaxisException("comilla inesperada en el parámetro " + n + ".");
            }
            i++;
        }
        String v = s.substring(inicio, i).strip();
        if (v.isEmpty()) {
            throw new SintaxisException("el parámetro " + n + " está vacío.");
        }
        return v;
    }

    private void espacios() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private boolean consumir(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private static String recorte(String x) {
        return x.length() > 40 ? x.substring(0, 40) + "…" : x;
    }
}
