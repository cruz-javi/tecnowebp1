package unexo.parser;

import java.util.List;

/** Comando extraído del asunto: NOMBRE["p1","p2",...] */
public record Comando(String nombre, List<String> parametros) {

    public String p(int i) {
        return parametros.get(i);
    }

    public int cantidad() {
        return parametros.size();
    }
}
