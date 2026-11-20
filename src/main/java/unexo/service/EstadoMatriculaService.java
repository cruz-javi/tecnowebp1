package unexo.service;

import unexo.config.AppConfig;
import unexo.repository.Db;
import unexo.router.Contexto;
import unexo.router.NegocioException;
import unexo.util.AppLogger;
import unexo.util.Respuesta;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** CU7 Gestión de Estado de Matrícula y Exposición API (Servidor SIS). */
public class EstadoMatriculaService {

    /** Transiciones permitidas (Tabla de estados del documento). */
    private static final Map<String, Set<String>> TRANSICIONES = Map.of(
            "EN_ESPERA", Set.of("PREINSCRITO", "ANULADO"),
            "PREINSCRITO", Set.of("MATRICULADO", "ANULADO"),
            "MATRICULADO", Set.of("RETIRADO", "FINALIZADO"),
            "RETIRADO", Set.of(),
            "FINALIZADO", Set.of(),
            "ANULADO", Set.of());

    private static final Set<String> SINCRONIZAN = Set.of("MATRICULADO", "RETIRADO", "FINALIZADO");

    private final SisClient sis = new SisClient();

    static boolean ocupaCupo(String estado) {
        return "PREINSCRITO".equals(estado) || "MATRICULADO".equals(estado);
    }

    static void historial(Connection c, int idMatricula, String anterior, String nuevo, String obs, String correo) throws SQLException {
        Db.actualizar(c, "INSERT INTO historial_estado_matricula (estado_anterior, estado_nuevo, observacion, correo_usuario, id_matricula) "
                + "VALUES (?,?,?,?,?)", anterior, nuevo, obs, correo, idMatricula);
    }

    /** ESTMAT */
    public Respuesta estmat(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> m = MatriculaService.matriculaPropia(ctx, ctx.p(0));
        Respuesta r = Respuesta.tabla("estado de la matrícula " + ctx.p(0),
                Db.consultar(MatriculaService.SELECT_MATRICULA + "WHERE m.id = ?", m.get("id")));
        Db.uno("SELECT estado, codigo_http, to_char(fecha_envio,'YYYY-MM-DD HH24:MI') AS fecha FROM sincronizacion_sis "
                + "WHERE id_matricula = ? ORDER BY id DESC LIMIT 1", m.get("id"))
                .ifPresent(s -> r.agregarTexto("Última sincronización con el SIS: " + s.get("estado") + " (" + s.get("fecha") + ")"));
        return r;
    }

    /** UPDEST["codigo_matricula","nuevo_estado","observacion"] */
    public Respuesta updest(Contexto ctx) throws SQLException {
        String nuevo = ctx.p(1);
        Object[] r = Db.transaccion(c -> {
            Map<String, Object> m = Db.uno(c, "SELECT * FROM matricula WHERE codigo = ? FOR UPDATE", ctx.p(0))
                    .orElseThrow(() -> MatriculaService.negocio("no existe la matrícula " + ctx.p(0) + "."));
            String actual = (String) m.get("estado");
            if (!TRANSICIONES.get(actual).contains(nuevo)) {
                throw MatriculaService.negocio("transición no permitida " + actual + " → " + nuevo
                        + ". Desde " + actual + " se permite: " + (TRANSICIONES.get(actual).isEmpty() ? "ninguna (estado final)" : TRANSICIONES.get(actual)) + ".");
            }
            Map<String, Object> g = Db.uno(c, "SELECT * FROM grupo WHERE id = ? FOR UPDATE", m.get("id_grupo")).get();
            if (!ocupaCupo(actual) && ocupaCupo(nuevo)) {
                if ((Integer) g.get("inscritos") >= (Integer) g.get("cupo_maximo")) {
                    throw MatriculaService.negocio("el grupo " + g.get("codigo") + " no tiene cupo para pasar la matrícula a " + nuevo + ".");
                }
                Db.actualizar(c, "UPDATE grupo SET inscritos = inscritos + 1 WHERE id = ?", g.get("id"));
            } else if (ocupaCupo(actual) && ("RETIRADO".equals(nuevo) || "ANULADO".equals(nuevo))) {
                Db.actualizar(c, "UPDATE grupo SET inscritos = inscritos - 1 WHERE id = ?", g.get("id"));
            }
            Db.actualizar(c, "UPDATE matricula SET estado = ? WHERE id = ?", nuevo, m.get("id"));
            historial(c, (Integer) m.get("id"), actual, nuevo, ctx.p(2), ctx.remitente());
            return new Object[]{m.get("id"), actual};
        });
        Respuesta resp = Respuesta.ok("matrícula " + ctx.p(0) + ": " + r[1] + " → " + nuevo + ".");
        if (SINCRONIZAN.contains(nuevo)) {
            resp.agregarTexto("Sincronización SIS: " + sincronizarSeguro((Integer) r[0], SisClient.operacionPara(nuevo)) + ".");
        }
        return resp;
    }

    /** HISEST */
    public Respuesta hisest(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> m = MatriculaService.matriculaPropia(ctx, ctx.p(0));
        return Respuesta.tabla("historial de la matrícula " + ctx.p(0), Db.consultar(
                "SELECT to_char(fecha_cambio,'YYYY-MM-DD HH24:MI') AS fecha, COALESCE(estado_anterior,'—') AS anterior, "
              + "estado_nuevo AS nuevo, observacion, correo_usuario AS usuario FROM historial_estado_matricula "
              + "WHERE id_matricula = ? ORDER BY fecha_cambio, id", m.get("id")));
    }

    /** SINSIS["codigo" | "*"]: reintenta una matrícula o todas las pendientes. */
    public Respuesta sinsis(Contexto ctx) throws SQLException, NegocioException {
        if (!"*".equals(ctx.p(0))) {
            Map<String, Object> m = Db.uno("SELECT id, estado FROM matricula WHERE codigo = ?", ctx.p(0))
                    .orElseThrow(() -> new NegocioException("no existe la matrícula " + ctx.p(0) + "."));
            int intento = intentos((Integer) m.get("id")) + 1;
            String r = sis.sincronizar((Integer) m.get("id"), SisClient.operacionPara((String) m.get("estado")), intento);
            return Respuesta.ok("matrícula " + ctx.p(0) + " enviada al SIS: " + r + ".");
        }
        List<Map<String, Object>> pendientes = Db.consultar(
                "SELECT DISTINCT ON (s.id_matricula) s.id_matricula, s.operacion, s.intentos, s.estado, m.codigo "
              + "FROM sincronizacion_sis s JOIN matricula m ON m.id = s.id_matricula ORDER BY s.id_matricula, s.id DESC");
        int enviados = 0;
        int total = 0;
        for (Map<String, Object> p : pendientes) {
            if (!"PENDIENTE".equals(p.get("estado"))) {
                continue;
            }
            total++;
            if ("ENVIADO".equals(sis.sincronizar((Integer) p.get("id_matricula"), (String) p.get("operacion"),
                    (Integer) p.get("intentos") + 1))) {
                enviados++;
            }
        }
        return Respuesta.ok("sincronización de pendientes: " + enviados + " de " + total + " enviadas.");
    }

    /** LISSIS["*" | estado] */
    public Respuesta lissis(Contexto ctx) throws SQLException {
        String sql = "SELECT s.id, m.codigo, s.operacion, to_char(s.fecha_envio,'YYYY-MM-DD HH24:MI') AS fecha, "
                + "s.codigo_http, s.intentos, s.estado FROM sincronizacion_sis s JOIN matricula m ON m.id = s.id_matricula ";
        List<Map<String, Object>> filas = "*".equals(ctx.p(0))
                ? Db.consultar(sql + "ORDER BY s.id DESC LIMIT 100")
                : Db.consultar(sql + "WHERE s.estado = ? ORDER BY s.id DESC LIMIT 100", ctx.p(0));
        return Respuesta.tabla("sincronizaciones con el SIS", filas);
    }

    /** Sincroniza sin interrumpir la respuesta si el SIS falla (queda PENDIENTE). */
    public String sincronizarSeguro(int idMatricula, String operacion) {
        try {
            return sis.sincronizar(idMatricula, operacion, 1);
        } catch (SQLException e) {
            AppLogger.error("No se pudo registrar la sincronización SIS de la matrícula " + idMatricula, e);
            return "PENDIENTE";
        }
    }

    /** Ejecutado por el daemon: reintenta pendientes automáticamente. */
    public void reintentarPendientes() {
        if (AppConfig.get("sis.url").isEmpty()) {
            return;
        }
        try {
            for (Map<String, Object> p : Db.consultar(
                    "SELECT DISTINCT ON (id_matricula) id_matricula, operacion, intentos, estado FROM sincronizacion_sis "
                  + "ORDER BY id_matricula, id DESC")) {
                if ("PENDIENTE".equals(p.get("estado"))) {
                    sis.sincronizar((Integer) p.get("id_matricula"), (String) p.get("operacion"), (Integer) p.get("intentos") + 1);
                }
            }
        } catch (SQLException e) {
            AppLogger.error("Reintento automático de sincronizaciones SIS", e);
        }
    }

    private static int intentos(int idMatricula) throws SQLException {
        return Db.uno("SELECT intentos FROM sincronizacion_sis WHERE id_matricula = ? ORDER BY id DESC LIMIT 1", idMatricula)
                .map(r -> (Integer) r.get("intentos")).orElse(0);
    }
}
