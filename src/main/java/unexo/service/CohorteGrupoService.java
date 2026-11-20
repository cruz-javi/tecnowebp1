package unexo.service;

import unexo.repository.Db;
import unexo.router.Contexto;
import unexo.router.NegocioException;
import unexo.util.Respuesta;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** CU4 Gestión de Planificar Cohortes y Apertura Operativa de Grupos. */
public class CohorteGrupoService {

    private static final String SELECT_GRUPO =
            "SELECT g.codigo, co.codigo AS cohorte, cu.codigo AS curso, g.modalidad, g.inscritos || '/' || g.cupo_maximo AS cupo, "
          + "g.estado, p.nombres || ' ' || p.apellidos AS instructor "
          + "FROM grupo g JOIN cohorte co ON co.id = g.id_cohorte JOIN curso cu ON cu.id = co.id_curso "
          + "LEFT JOIN instructor i ON i.id = g.id_instructor LEFT JOIN persona p ON p.ci = i.ci_persona ";

    public Respuesta inscoh(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> curso = CatalogoService.curso(ctx.p(1));
        if (!(Boolean) curso.get("estado")) {
            throw new NegocioException("el curso " + ctx.p(1) + " está inactivo.");
        }
        fechas(ctx, 3, 4);
        Db.actualizar("INSERT INTO cohorte (codigo, gestion, fecha_inicio, fecha_fin, cupo_minimo, id_curso) VALUES (?,?,?,?,?,?)",
                ctx.p(0), ctx.p(2), ctx.pFecha(3), ctx.pFecha(4), ctx.pInt(5), curso.get("id"));
        return Respuesta.ok("cohorte " + ctx.p(0) + " planificada para el curso " + ctx.p(1) + " (gestión " + ctx.p(2) + ").");
    }

    public Respuesta updcoh(Contexto ctx) throws SQLException, NegocioException {
        fechas(ctx, 1, 2);
        int n = Db.actualizar("UPDATE cohorte SET fecha_inicio=?, fecha_fin=?, cupo_minimo=?, estado=? WHERE codigo=?",
                ctx.pFecha(1), ctx.pFecha(2), ctx.pInt(3), ctx.p(4), ctx.p(0));
        if (n == 0) {
            throw new NegocioException("no existe la cohorte " + ctx.p(0) + ".");
        }
        return Respuesta.ok("cohorte " + ctx.p(0) + " actualizada (estado " + ctx.p(4) + ").");
    }

    public Respuesta liscoh(Contexto ctx) throws SQLException {
        String sql = "SELECT co.codigo, cu.codigo AS curso, co.gestion, co.fecha_inicio, co.fecha_fin, co.cupo_minimo, co.estado, "
                + "COUNT(g.id) AS grupos FROM cohorte co JOIN curso cu ON cu.id = co.id_curso LEFT JOIN grupo g ON g.id_cohorte = co.id ";
        List<Map<String, Object>> filas = "*".equals(ctx.p(0))
                ? Db.consultar(sql + "GROUP BY co.id, cu.codigo ORDER BY co.fecha_inicio DESC")
                : Db.consultar(sql + "WHERE cu.codigo = ? GROUP BY co.id, cu.codigo ORDER BY co.fecha_inicio DESC", ctx.p(0));
        return Respuesta.tabla("cohortes", filas);
    }

    public Respuesta insins(Contexto ctx) throws SQLException, NegocioException {
        if (Db.uno("SELECT 1 FROM persona WHERE ci = ?", ctx.p(0)).isEmpty()) {
            throw new NegocioException("no existe la persona con CI " + ctx.p(0) + ". Regístrela primero con INSUSU.");
        }
        int id = Db.insertarId("INSERT INTO instructor (ci_persona, especialidad) VALUES (?,?) RETURNING id", ctx.p(0), ctx.p(1));
        return Respuesta.ok("instructor registrado con id " + id + ".");
    }

    public Respuesta lisins(Contexto ctx) throws SQLException {
        return Respuesta.tabla("instructores", Db.consultar(
                "SELECT i.id, p.ci, p.nombres || ' ' || p.apellidos AS nombre, i.especialidad, i.estado "
              + "FROM instructor i JOIN persona p ON p.ci = i.ci_persona ORDER BY p.apellidos"));
    }

    public Respuesta insgru(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> coh = Db.uno("SELECT * FROM cohorte WHERE codigo = ?", ctx.p(1))
                .orElseThrow(() -> new NegocioException("no existe la cohorte " + ctx.p(1) + "."));
        if (!List.of("PLANIFICADA", "EN_CURSO").contains(coh.get("estado"))) {
            throw new NegocioException("la cohorte " + ctx.p(1) + " está " + coh.get("estado") + ".");
        }
        if (Db.uno("SELECT 1 FROM instructor WHERE id = ? AND estado", ctx.pInt(2)).isEmpty()) {
            throw new NegocioException("no existe un instructor activo con id " + ctx.p(2) + ".");
        }
        if (ctx.pInt(4) <= 0) {
            throw new NegocioException("el cupo máximo debe ser mayor a 0.");
        }
        Db.actualizar("INSERT INTO grupo (codigo, modalidad, cupo_maximo, id_cohorte, id_instructor) VALUES (?,?,?,?,?)",
                ctx.p(0), ctx.p(3), ctx.pInt(4), coh.get("id"), ctx.pInt(2));
        return Respuesta.ok("grupo " + ctx.p(0) + " creado en estado PLANIFICADO (" + ctx.p(3) + ", cupo " + ctx.p(4) + ").");
    }

    public Respuesta abrgru(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> g = grupo(ctx.p(0));
        if (!"PLANIFICADO".equals(g.get("estado"))) {
            throw new NegocioException("solo se puede abrir un grupo PLANIFICADO; el grupo está " + g.get("estado") + ".");
        }
        if (g.get("id_instructor") == null) {
            throw new NegocioException("asigne un instructor al grupo antes de abrirlo.");
        }
        long ofertas = (Long) Db.uno("SELECT COUNT(*) AS n FROM oferta_comercial o JOIN cohorte co ON co.id_curso = o.id_curso "
                + "WHERE co.id = ? AND o.estado AND CURRENT_DATE BETWEEN o.fecha_inicio AND o.fecha_fin", g.get("id_cohorte")).get().get("n");
        if (ofertas == 0) {
            throw new NegocioException("el curso no tiene una oferta comercial vigente (INSOFE).");
        }
        Db.actualizar("UPDATE grupo SET estado='ABIERTO', fecha_apertura=CURRENT_DATE WHERE id=?", g.get("id"));
        long horarios = (Long) Db.uno("SELECT COUNT(*) AS n FROM horario_clase WHERE id_grupo = ? AND estado", g.get("id")).get().get("n");
        Respuesta r = Respuesta.ok("grupo " + ctx.p(0) + " ABIERTO; ya recibe matrículas.");
        if (horarios == 0) {
            r.agregarTexto("Aviso: el grupo aún no tiene horarios. Regístrelos con INSHOR.");
        }
        return r;
    }

    public Respuesta cergru(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> g = grupo(ctx.p(0));
        if (!"ABIERTO".equals(g.get("estado"))) {
            throw new NegocioException("solo se puede cerrar un grupo ABIERTO; el grupo está " + g.get("estado") + ".");
        }
        Db.actualizar("UPDATE grupo SET estado='CERRADO' WHERE id=?", g.get("id"));
        Map<String, Object> coh = Db.uno("SELECT cupo_minimo FROM cohorte WHERE id = ?", g.get("id_cohorte")).get();
        Respuesta r = Respuesta.ok("grupo " + ctx.p(0) + " CERRADO con " + g.get("inscritos") + " inscrito(s).");
        if ((Integer) g.get("inscritos") < (Integer) coh.get("cupo_minimo")) {
            r.agregarTexto("Aviso: el grupo no alcanzó el cupo mínimo de la cohorte (" + coh.get("cupo_minimo") + ").");
        }
        return r;
    }

    public Respuesta cangru(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> g = grupo(ctx.p(0));
        if ("CANCELADO".equals(g.get("estado"))) {
            throw new NegocioException("el grupo ya está cancelado.");
        }
        long activas = (Long) Db.uno("SELECT COUNT(*) AS n FROM matricula WHERE id_grupo = ? AND estado IN ('PREINSCRITO','MATRICULADO')",
                g.get("id")).get().get("n");
        if (activas > 0) {
            throw new NegocioException("el grupo tiene " + activas + " matrícula(s) activa(s); muévalas con CAMGRU o anúlelas con UPDEST.");
        }
        Db.actualizar("UPDATE grupo SET estado='CANCELADO' WHERE id=?", g.get("id"));
        return Respuesta.ok("grupo " + ctx.p(0) + " CANCELADO. Motivo: " + ctx.p(1));
    }

    public Respuesta lisgru(Contexto ctx) throws SQLException {
        String filtroCliente = ctx.esPropietario() ? "" : "g.estado = 'ABIERTO' AND ";
        List<Map<String, Object>> filas = "*".equals(ctx.p(0))
                ? Db.consultar(SELECT_GRUPO + "WHERE " + filtroCliente + "TRUE ORDER BY g.codigo")
                : Db.consultar(SELECT_GRUPO + "WHERE " + filtroCliente + "co.codigo = ? ORDER BY g.codigo", ctx.p(0));
        return Respuesta.tabla(ctx.esPropietario() ? "grupos" : "grupos abiertos", filas);
    }

    // ---------- auxiliares ----------

    static Map<String, Object> grupo(String codigo) throws SQLException, NegocioException {
        return Db.uno("SELECT * FROM grupo WHERE codigo = ?", codigo)
                .orElseThrow(() -> new NegocioException("no existe el grupo " + codigo + "."));
    }

    private static void fechas(Contexto ctx, int ini, int fin) throws NegocioException {
        if (ctx.pFecha(fin).before(ctx.pFecha(ini))) {
            throw new NegocioException("fecha_fin debe ser mayor o igual a fecha_inicio.");
        }
    }
}
