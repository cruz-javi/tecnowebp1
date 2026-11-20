package unexo.service;

import unexo.repository.Db;
import unexo.router.Contexto;
import unexo.router.NegocioException;
import unexo.util.Respuesta;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** CU5 Gestión de Configuración Logística Operativa de Clases (Virtual/Presencial). */
public class LogisticaService {

    public Respuesta insaul(Contexto ctx) throws SQLException, NegocioException {
        if (ctx.pInt(2) <= 0) {
            throw new NegocioException("la capacidad debe ser mayor a 0.");
        }
        int id = Db.insertarId("INSERT INTO aula (nombre, ubicacion, capacidad) VALUES (?,?,?) RETURNING id",
                ctx.p(0), ctx.p(1), ctx.pInt(2));
        return Respuesta.ok("aula \"" + ctx.p(0) + "\" registrada con id " + id + ".");
    }

    public Respuesta lisaul(Contexto ctx) throws SQLException {
        return Respuesta.tabla("aulas", Db.consultar("SELECT id, nombre, ubicacion, capacidad, estado FROM aula ORDER BY nombre"));
    }

    /** INSHOR["codigo_grupo","dia","hora_inicio","hora_fin","modalidad","id_aula|-","plataforma|-","enlace|-"] */
    public Respuesta inshor(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> g = CohorteGrupoService.grupo(ctx.p(0));
        if (!List.of("PLANIFICADO", "ABIERTO").contains(g.get("estado"))) {
            throw new NegocioException("el grupo " + ctx.p(0) + " está " + g.get("estado") + "; no admite horarios.");
        }
        int id = Db.transaccion(c -> {
            validar(c, (Integer) g.get("id"), (Integer) g.get("cupo_maximo"), ctx, 1, null);
            return Db.insertarId(c, "INSERT INTO horario_clase (dia_semana, hora_inicio, hora_fin, modalidad, plataforma, enlace, id_grupo, id_aula) "
                            + "VALUES (?,?,?,?,?,?,?,?) RETURNING id",
                    ctx.p(1), ctx.pHora(2), ctx.pHora(3), ctx.p(4), ctx.p(6), ctx.p(7), g.get("id"),
                    "PRESENCIAL".equals(ctx.p(4)) ? ctx.pInt(5) : null);
        });
        return Respuesta.ok("horario " + id + " registrado para el grupo " + ctx.p(0) + ": " + ctx.p(1) + " "
                + ctx.p(2) + "–" + ctx.p(3) + " (" + ctx.p(4) + ").");
    }

    /** UPDHOR["id","dia","hora_inicio","hora_fin","modalidad","id_aula|-","plataforma|-","enlace|-"] */
    public Respuesta updhor(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> h = Db.uno("SELECT h.*, g.cupo_maximo FROM horario_clase h JOIN grupo g ON g.id = h.id_grupo WHERE h.id = ? AND h.estado",
                ctx.pInt(0)).orElseThrow(() -> new NegocioException("no existe el horario " + ctx.p(0) + "."));
        Db.transaccion(c -> {
            validar(c, (Integer) h.get("id_grupo"), (Integer) h.get("cupo_maximo"), ctx, 1, ctx.pInt(0));
            return Db.actualizar(c, "UPDATE horario_clase SET dia_semana=?, hora_inicio=?, hora_fin=?, modalidad=?, plataforma=?, enlace=?, id_aula=? WHERE id=?",
                    ctx.p(1), ctx.pHora(2), ctx.pHora(3), ctx.p(4), ctx.p(6), ctx.p(7),
                    "PRESENCIAL".equals(ctx.p(4)) ? ctx.pInt(5) : null, ctx.pInt(0));
        });
        return Respuesta.ok("horario " + ctx.p(0) + " actualizado.");
    }

    public Respuesta delhor(Contexto ctx) throws SQLException, NegocioException {
        int n = Db.actualizar("UPDATE horario_clase SET estado = FALSE WHERE id = ? AND estado", ctx.pInt(0));
        if (n == 0) {
            throw new NegocioException("no existe el horario " + ctx.p(0) + ".");
        }
        return Respuesta.ok("horario " + ctx.p(0) + " eliminado.");
    }

    public Respuesta lishor(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> g = CohorteGrupoService.grupo(ctx.p(0));
        return Respuesta.tabla("horarios del grupo " + ctx.p(0), Db.consultar(
                "SELECT h.id, h.dia_semana AS dia, to_char(h.hora_inicio,'HH24:MI') AS inicio, to_char(h.hora_fin,'HH24:MI') AS fin, "
              + "h.modalidad, COALESCE(a.nombre || ' - ' || a.ubicacion, h.plataforma || ' ' || h.enlace) AS lugar "
              + "FROM horario_clase h LEFT JOIN aula a ON a.id = h.id_aula WHERE h.id_grupo = ? AND h.estado "
              + "ORDER BY array_position(ARRAY['LUNES','MARTES','MIERCOLES','JUEVES','VIERNES','SABADO','DOMINGO']::varchar[], h.dia_semana), h.hora_inicio",
                g.get("id")));
    }

    /** Valida modalidad, capacidad y choques de horario (mismo aula o mismo grupo). */
    private static void validar(Connection c, int idGrupo, int cupo, Contexto ctx, int base, Integer excluirId)
            throws SQLException {
        String dia = ctx.p(base);
        if (!ctx.pHora(base + 2).after(ctx.pHora(base + 1))) {
            throw new SQLException("la hora de fin debe ser posterior a la hora de inicio.", "UNX01");
        }
        String modalidad = ctx.p(base + 3);
        Integer idAula = ctx.pInt(base + 4);
        int excluir = excluirId == null ? -1 : excluirId;
        if ("PRESENCIAL".equals(modalidad)) {
            if (idAula == null) {
                throw new SQLException("la modalidad PRESENCIAL requiere un aula (id_aula).", "UNX01");
            }
            Optional<Map<String, Object>> aula = Db.uno(c, "SELECT capacidad FROM aula WHERE id = ? AND estado", idAula);
            if (aula.isEmpty()) {
                throw new SQLException("no existe un aula activa con id " + idAula + ".", "UNX01");
            }
            if ((Integer) aula.get().get("capacidad") < cupo) {
                throw new SQLException("el aula tiene capacidad " + aula.get().get("capacidad") + " y el grupo admite " + cupo + ".", "UNX01");
            }
            Optional<Map<String, Object>> choque = Db.uno(c,
                    "SELECT g.codigo, to_char(h.hora_inicio,'HH24:MI') AS ini, to_char(h.hora_fin,'HH24:MI') AS fin "
                  + "FROM horario_clase h JOIN grupo g ON g.id = h.id_grupo "
                  + "WHERE h.estado AND h.id_aula = ? AND h.dia_semana = ? AND h.hora_inicio < ? AND h.hora_fin > ? AND h.id <> ? "
                  + "AND g.estado IN ('PLANIFICADO','ABIERTO') LIMIT 1",
                    idAula, dia, ctx.pHora(base + 2), ctx.pHora(base + 1), excluir);
            if (choque.isPresent()) {
                Map<String, Object> ch = choque.get();
                throw new SQLException("choque de horario con el grupo " + ch.get("codigo") + " en el aula " + idAula
                        + " (" + dia + " " + ch.get("ini") + "–" + ch.get("fin") + ").", "UNX01");
            }
        } else if (ctx.p(base + 5) == null || ctx.p(base + 6) == null) {
            throw new SQLException("la modalidad VIRTUAL requiere plataforma y enlace.", "UNX01");
        }
        Optional<Map<String, Object>> propio = Db.uno(c,
                "SELECT to_char(hora_inicio,'HH24:MI') AS ini, to_char(hora_fin,'HH24:MI') AS fin FROM horario_clase "
              + "WHERE estado AND id_grupo = ? AND dia_semana = ? AND hora_inicio < ? AND hora_fin > ? AND id <> ? LIMIT 1",
                idGrupo, dia, ctx.pHora(base + 2), ctx.pHora(base + 1), excluir);
        if (propio.isPresent()) {
            throw new SQLException("el grupo ya tiene clase el " + dia + " de " + propio.get().get("ini") + " a "
                    + propio.get().get("fin") + ".", "UNX01");
        }
    }
}
