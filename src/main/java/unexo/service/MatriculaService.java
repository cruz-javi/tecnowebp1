package unexo.service;

import unexo.repository.Db;
import unexo.router.Contexto;
import unexo.router.NegocioException;
import unexo.util.Respuesta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** CU6 Gestión de Recepción y Matriculación Flexible de Estudiantes. */
public class MatriculaService {

    private final EstadoMatriculaService estados;

    public MatriculaService(EstadoMatriculaService estados) {
        this.estados = estados;
    }

    static final String SELECT_MATRICULA =
            "SELECT m.codigo, p.ci, p.nombres || ' ' || p.apellidos AS estudiante, cu.codigo AS curso, g.codigo AS grupo, "
          + "m.tipo_pago, m.nro_cuotas, m.monto_total, m.descuento, m.estado, to_char(m.fecha,'YYYY-MM-DD') AS fecha "
          + "FROM matricula m JOIN cliente cl ON cl.id = m.id_cliente JOIN persona p ON p.ci = cl.ci_persona "
          + "JOIN grupo g ON g.id = m.id_grupo JOIN cohorte co ON co.id = g.id_cohorte JOIN curso cu ON cu.id = co.id_curso ";

    /** INSMAT["ci_cliente","codigo_grupo","CONTADO|CUOTAS","nro_cuotas"] */
    public Respuesta insmat(Contexto ctx) throws SQLException, NegocioException {
        String ci = ctx.p(0);
        ctx.exigirPropio(ci);
        String tipo = ctx.p(2);
        int nro = "CONTADO".equals(tipo) ? 1 : ctx.pInt(3);
        if ("CUOTAS".equals(tipo) && nro < 2) {
            throw new NegocioException("para pagar en cuotas indique 2 o más cuotas; para 1 cuota use CONTADO.");
        }

        Object[] res = Db.transaccion(c -> {
            Map<String, Object> cliente = Db.uno(c, "SELECT id FROM cliente WHERE ci_persona = ?", ci)
                    .orElseThrow(() -> negocio("el CI " + ci + " no está registrado como cliente (REGCLI o INSCLI)."));
            // Bloqueo de la fila del grupo: evita sobreinscripción con solicitudes simultáneas.
            Map<String, Object> g = Db.uno(c, "SELECT g.*, co.id_curso FROM grupo g JOIN cohorte co ON co.id = g.id_cohorte "
                    + "WHERE g.codigo = ? FOR UPDATE OF g", ctx.p(1))
                    .orElseThrow(() -> negocio("no existe el grupo " + ctx.p(1) + "."));
            if (!"ABIERTO".equals(g.get("estado"))) {
                throw negocio("el grupo " + ctx.p(1) + " no está abierto (estado " + g.get("estado") + ").");
            }
            Map<String, Object> oferta = Db.uno(c, CatalogoService.OFERTA_VIGENTE, g.get("id_curso"))
                    .orElseThrow(() -> negocio("el curso no tiene una oferta comercial vigente."));
            int max = (Integer) oferta.get("max_cuotas");
            if (nro > max) {
                throw negocio("INSMAT parámetro 4 (nro_cuotas) debe ser ≤ " + max + " según la oferta vigente.");
            }
            if (Db.uno(c, "SELECT 1 FROM matricula WHERE id_cliente = ? AND id_grupo = ?", cliente.get("id"), g.get("id")).isPresent()) {
                throw negocio("el cliente " + ci + " ya tiene una matrícula en el grupo " + ctx.p(1) + ".");
            }

            BigDecimal precio = (BigDecimal) oferta.get("precio");
            BigDecimal descuento = BigDecimal.ZERO;
            if ("CONTADO".equals(tipo)) {
                descuento = precio.multiply((BigDecimal) oferta.get("descuento_contado"))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            }
            BigDecimal total = precio.subtract(descuento);
            boolean hayCupo = (Integer) g.get("inscritos") < (Integer) g.get("cupo_maximo");
            String estado = hayCupo ? "PREINSCRITO" : "EN_ESPERA";

            long id = (Long) Db.uno(c, "SELECT nextval(pg_get_serial_sequence('matricula','id')) AS id").get().get("id");
            String codigo = String.format("MAT-%04d", id);
            Db.actualizar(c, "INSERT INTO matricula (id, codigo, monto_total, descuento, tipo_pago, nro_cuotas, estado, id_cliente, id_grupo, id_oferta) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    (int) id, codigo, total, descuento, tipo, nro, estado, cliente.get("id"), g.get("id"), oferta.get("id"));

            List<Map<String, Object>> plan = generarCuotas(c, (int) id, total, nro, (String) oferta.get("moneda"));
            if (hayCupo) {
                Db.actualizar(c, "UPDATE grupo SET inscritos = inscritos + 1 WHERE id = ?", g.get("id"));
            }
            EstadoMatriculaService.historial(c, (int) id, null, estado, "Solicitud de matrícula (" + tipo + ")", ctx.remitente());
            return new Object[]{codigo, estado, total, oferta.get("moneda"), plan};
        });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plan = (List<Map<String, Object>>) res[4];
        Respuesta r = Respuesta.ok("matrícula " + res[0] + " registrada en estado " + res[1] + ". Total: " + res[2] + " " + res[3] + ".");
        if ("EN_ESPERA".equals(res[1])) {
            r.agregarTexto("El grupo no tiene cupo: quedó en lista de espera y se le avisará cuando se libere un lugar.");
        } else {
            r.agregarTexto("La matrícula pasará a MATRICULADO al registrarse el pago de la primera cuota.");
        }
        r.agregarTexto("Plan de pagos:");
        r.agregarTabla(plan);
        return r;
    }

    /** GETMAT */
    public Respuesta getmat(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> m = matriculaPropia(ctx, ctx.p(0));
        Respuesta r = Respuesta.tabla("matrícula " + ctx.p(0), Db.consultar(SELECT_MATRICULA + "WHERE m.id = ?", m.get("id")));
        r.agregarTexto("Cuotas:");
        r.agregarTabla(cuotas((Integer) m.get("id")));
        return r;
    }

    /** LISMAT["*" | ci | codigo_grupo] */
    public Respuesta lismat(Contexto ctx) throws SQLException {
        String f = ctx.p(0);
        List<Map<String, Object>> filas;
        if (!ctx.esPropietario()) {
            filas = Db.consultar(SELECT_MATRICULA + "WHERE p.ci = ? ORDER BY m.fecha DESC", ctx.usuario().ci());
        } else if ("*".equals(f)) {
            filas = Db.consultar(SELECT_MATRICULA + "ORDER BY m.fecha DESC");
        } else {
            filas = Db.consultar(SELECT_MATRICULA + "WHERE p.ci = ? OR g.codigo = ? ORDER BY m.fecha DESC", f, f);
        }
        return Respuesta.tabla("matrículas", filas);
    }

    /** CAMGRU["codigo_matricula","codigo_grupo_nuevo"]: misma cohorte, con cupo. */
    public Respuesta camgru(Contexto ctx) throws SQLException {
        String[] r = Db.transaccion(c -> {
            Map<String, Object> m = Db.uno(c, "SELECT m.*, g.id_cohorte, g.codigo AS grupo FROM matricula m JOIN grupo g ON g.id = m.id_grupo "
                    + "WHERE m.codigo = ? FOR UPDATE OF m", ctx.p(0))
                    .orElseThrow(() -> negocio("no existe la matrícula " + ctx.p(0) + "."));
            String est = (String) m.get("estado");
            if (!List.of("EN_ESPERA", "PREINSCRITO", "MATRICULADO").contains(est)) {
                throw negocio("la matrícula está " + est + "; no se puede cambiar de grupo.");
            }
            Map<String, Object> nuevo = Db.uno(c, "SELECT * FROM grupo WHERE codigo = ? FOR UPDATE", ctx.p(1))
                    .orElseThrow(() -> negocio("no existe el grupo " + ctx.p(1) + "."));
            if (nuevo.get("id").equals(m.get("id_grupo"))) {
                throw negocio("la matrícula ya pertenece al grupo " + ctx.p(1) + ".");
            }
            if (!nuevo.get("id_cohorte").equals(m.get("id_cohorte"))) {
                throw negocio("el grupo " + ctx.p(1) + " no pertenece a la misma cohorte.");
            }
            if (!"ABIERTO".equals(nuevo.get("estado"))) {
                throw negocio("el grupo " + ctx.p(1) + " no está abierto.");
            }
            if ((Integer) nuevo.get("inscritos") >= (Integer) nuevo.get("cupo_maximo")) {
                throw negocio("el grupo " + ctx.p(1) + " no tiene cupo disponible.");
            }
            if (EstadoMatriculaService.ocupaCupo(est)) {
                Db.actualizar(c, "UPDATE grupo SET inscritos = inscritos - 1 WHERE id = ?", m.get("id_grupo"));
            }
            Db.actualizar(c, "UPDATE grupo SET inscritos = inscritos + 1 WHERE id = ?", nuevo.get("id"));
            String nuevoEstado = "EN_ESPERA".equals(est) ? "PREINSCRITO" : est;
            Db.actualizar(c, "UPDATE matricula SET id_grupo = ?, estado = ? WHERE id = ?", nuevo.get("id"), nuevoEstado, m.get("id"));
            EstadoMatriculaService.historial(c, (Integer) m.get("id"), est, nuevoEstado,
                    "Cambio de grupo " + m.get("grupo") + " → " + ctx.p(1), ctx.remitente());
            return new String[]{(String) m.get("grupo"), nuevoEstado};
        });
        return Respuesta.ok("matrícula " + ctx.p(0) + " movida del grupo " + r[0] + " al " + ctx.p(1) + " (estado " + r[1] + ").");
    }

    /** PAGCUO["codigo_matricula","nro_cuota"] */
    public Respuesta pagcuo(Contexto ctx) throws SQLException {
        Object[] r = Db.transaccion(c -> {
            Map<String, Object> m = Db.uno(c, "SELECT * FROM matricula WHERE codigo = ? FOR UPDATE", ctx.p(0))
                    .orElseThrow(() -> negocio("no existe la matrícula " + ctx.p(0) + "."));
            String est = (String) m.get("estado");
            if (!List.of("PREINSCRITO", "MATRICULADO").contains(est)) {
                throw negocio("la matrícula está " + est + "; no admite pagos.");
            }
            Map<String, Object> cuota = Db.uno(c, "SELECT * FROM cuota WHERE id_matricula = ? AND numero = ?", m.get("id"), ctx.pInt(1))
                    .orElseThrow(() -> negocio("la matrícula no tiene la cuota " + ctx.p(1) + "."));
            if ("PAGADA".equals(cuota.get("estado"))) {
                throw negocio("la cuota " + ctx.p(1) + " ya fue pagada el " + cuota.get("fecha_pago") + ".");
            }
            long anteriores = (Long) Db.uno(c, "SELECT COUNT(*) AS n FROM cuota WHERE id_matricula = ? AND numero < ? AND estado <> 'PAGADA'",
                    m.get("id"), ctx.pInt(1)).get().get("n");
            if (anteriores > 0) {
                throw negocio("existen " + anteriores + " cuota(s) anterior(es) pendiente(s); páguelas en orden.");
            }
            Db.actualizar(c, "UPDATE cuota SET estado='PAGADA', fecha_pago=CURRENT_DATE WHERE id = ?", cuota.get("id"));
            boolean activar = "PREINSCRITO".equals(est);
            if (activar) {
                Db.actualizar(c, "UPDATE matricula SET estado='MATRICULADO' WHERE id = ?", m.get("id"));
                EstadoMatriculaService.historial(c, (Integer) m.get("id"), est, "MATRICULADO",
                        "Pago de la cuota " + ctx.p(1), ctx.remitente());
            }
            return new Object[]{m.get("id"), activar, cuota.get("monto")};
        });
        Respuesta resp = Respuesta.ok("cuota " + ctx.p(1) + " de la matrícula " + ctx.p(0) + " pagada (" + r[2] + ").");
        if ((Boolean) r[1]) {
            String sis = estados.sincronizarSeguro((Integer) r[0], "ALTA");
            resp.agregarTexto("La matrícula pasó a MATRICULADO. Sincronización SIS: " + sis + ".");
        }
        resp.agregarTabla(cuotas((Integer) r[0]));
        return resp;
    }

    /** LISCUO */
    public Respuesta liscuo(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> m = matriculaPropia(ctx, ctx.p(0));
        return Respuesta.tabla("cuotas de la matrícula " + ctx.p(0), cuotas((Integer) m.get("id")));
    }

    // ---------- auxiliares ----------

    static List<Map<String, Object>> cuotas(int idMatricula) throws SQLException {
        Db.actualizar("UPDATE cuota SET estado='VENCIDA' WHERE id_matricula = ? AND estado='PENDIENTE' AND fecha_vencimiento < CURRENT_DATE",
                idMatricula);
        return Db.consultar("SELECT numero AS cuota, monto, fecha_vencimiento AS vence, fecha_pago AS pagada, estado "
                + "FROM cuota WHERE id_matricula = ? ORDER BY numero", idMatricula);
    }

    /** Busca la matrícula y verifica que un cliente solo vea las suyas. */
    static Map<String, Object> matriculaPropia(Contexto ctx, String codigo) throws SQLException, NegocioException {
        Map<String, Object> m = Db.uno("SELECT m.*, cl.ci_persona FROM matricula m JOIN cliente cl ON cl.id = m.id_cliente WHERE m.codigo = ?", codigo)
                .orElseThrow(() -> new NegocioException("no existe la matrícula " + codigo + "."));
        ctx.exigirPropio((String) m.get("ci_persona"));
        return m;
    }

    private static List<Map<String, Object>> generarCuotas(Connection c, int idMatricula, BigDecimal total, int n, String moneda)
            throws SQLException {
        BigDecimal base = total.divide(BigDecimal.valueOf(n), 2, RoundingMode.DOWN);
        BigDecimal acumulado = BigDecimal.ZERO;
        LocalDate primera = LocalDate.now().plusDays(7);
        List<Map<String, Object>> plan = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            BigDecimal monto = i == n ? total.subtract(acumulado) : base;
            acumulado = acumulado.add(monto);
            LocalDate vence = primera.plusMonths(i - 1L);
            Db.actualizar(c, "INSERT INTO cuota (numero, monto, fecha_vencimiento, id_matricula) VALUES (?,?,?,?)",
                    i, monto, java.sql.Date.valueOf(vence), idMatricula);
            Map<String, Object> fila = new java.util.LinkedHashMap<>();
            fila.put("cuota", i);
            fila.put("monto", monto + " " + moneda);
            fila.put("vence", vence.toString());
            plan.add(fila);
        }
        return plan;
    }

    static SQLException negocio(String mensaje) {
        return new SQLException(mensaje, "UNX01");
    }
}
