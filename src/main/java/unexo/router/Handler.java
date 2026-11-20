package unexo.router;

import unexo.util.Respuesta;

import java.sql.SQLException;

@FunctionalInterface
public interface Handler {
    Respuesta ejecutar(Contexto ctx) throws NegocioException, SQLException;
}
