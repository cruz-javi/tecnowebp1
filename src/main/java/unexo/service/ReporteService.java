package unexo.service;

import unexo.repository.Db;
import unexo.router.Contexto;
import unexo.router.NegocioException;
import unexo.util.Html;
import unexo.util.Respuesta;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** CU8 Reportes y Estadísticas. */
public class ReporteService {

    /** REPMAT["fecha_ini","fecha_fin"] */
    public Respuesta repmat(Contexto ctx) throws SQLException, NegocioException {
        rango(ctx);
        List<Map<String, Object>> filas = Db.consultar(
                "SELECT cu.codigo AS curso, m.estado, COUNT(*) AS cantidad FROM matricula m "
              + "JOIN grupo g ON g.id = m.id_grupo JOIN cohorte co ON co.id = g.id_cohorte JOIN curso cu ON cu.id = co.id_curso "
              + "WHERE m.fecha::date BETWEEN ? AND ? GROUP BY cu.codigo, m.estado ORDER BY cu.codigo, m.estado",
                ctx.pFecha(0), ctx.pFecha(1));
        Respuesta r = Respuesta.tabla("matrículas del " + ctx.p(0) + " al " + ctx.p(1), filas);
        List<Map<String, Object>> porEstado = Db.consultar(
                "SELECT estado, COUNT(*) AS n FROM matricula WHERE fecha::date BETWEEN ? AND ? GROUP BY estado ORDER BY n DESC",
                ctx.pFecha(0), ctx.pFecha(1));
        grafico(r, "Matrículas por estado", porEstado, "estado", "n");
        return r;
    }

    /** REPING["fecha_ini","fecha_fin"]: cuotas pagadas en el rango. */
    public Respuesta reping(Contexto ctx) throws SQLException, NegocioException {
        rango(ctx);
        List<Map<String, Object>> filas = Db.consultar(
                "SELECT cu.codigo AS curso, cu.nombre, COUNT(q.id) AS cuotas_pagadas, SUM(q.monto) AS ingresos "
              + "FROM cuota q JOIN matricula m ON m.id = q.id_matricula JOIN grupo g ON g.id = m.id_grupo "
              + "JOIN cohorte co ON co.id = g.id_cohorte JOIN curso cu ON cu.id = co.id_curso "
              + "WHERE q.estado = 'PAGADA' AND q.fecha_pago BETWEEN ? AND ? GROUP BY cu.codigo, cu.nombre ORDER BY ingresos DESC",
                ctx.pFecha(0), ctx.pFecha(1));
        BigDecimal total = filas.stream().map(f -> (BigDecimal) f.get("ingresos")).reduce(BigDecimal.ZERO, BigDecimal::add);
        Respuesta r = Respuesta.tabla("ingresos del " + ctx.p(0) + " al " + ctx.p(1) + " — total " + total, filas);
        grafico(r, "Ingresos por curso", filas, "curso", "ingresos");
        return r;
    }

    /** REPGRU["codigo_cohorte" | "*"]: ocupación de grupos. */
    public Respuesta repgru(Contexto ctx) throws SQLException {
        String sql = "SELECT g.codigo AS grupo, co.codigo AS cohorte, g.modalidad, g.estado, g.inscritos, g.cupo_maximo AS cupo, "
                + "ROUND(100.0 * g.inscritos / g.cupo_maximo, 1) AS ocupacion_pct, "
                + "(SELECT COUNT(*) FROM matricula m WHERE m.id_grupo = g.id AND m.estado = 'EN_ESPERA') AS en_espera "
                + "FROM grupo g JOIN cohorte co ON co.id = g.id_cohorte ";
        List<Map<String, Object>> filas = "*".equals(ctx.p(0))
                ? Db.consultar(sql + "WHERE g.estado <> 'CANCELADO' ORDER BY ocupacion_pct DESC")
                : Db.consultar(sql + "WHERE co.codigo = ? ORDER BY ocupacion_pct DESC", ctx.p(0));
        Respuesta r = Respuesta.tabla("ocupación de grupos", filas);
        grafico(r, "Ocupación (%)", filas, "grupo", "ocupacion_pct");
        return r;
    }

    /** REPCUR["gestion"] */
    public Respuesta repcur(Contexto ctx) throws SQLException {
        return Respuesta.tabla("cursos de la gestión " + ctx.p(0), Db.consultar(
                "SELECT cu.codigo AS curso, cu.nombre, COUNT(DISTINCT co.id) AS cohortes, COUNT(DISTINCT g.id) AS grupos, "
              + "COUNT(m.id) FILTER (WHERE m.estado IN ('MATRICULADO','FINALIZADO')) AS matriculados "
              + "FROM curso cu JOIN cohorte co ON co.id_curso = cu.id AND co.gestion = ? "
              + "LEFT JOIN grupo g ON g.id_cohorte = co.id LEFT JOIN matricula m ON m.id_grupo = g.id "
              + "GROUP BY cu.codigo, cu.nombre ORDER BY matriculados DESC", ctx.p(0)));
    }

    /** ESTGEN["*"] */
    public Respuesta estgen(Contexto ctx) throws SQLException {
        Map<String, Object> t = Db.uno(
                "SELECT (SELECT COUNT(*) FROM cliente) AS clientes, (SELECT COUNT(*) FROM curso WHERE estado) AS cursos, "
              + "(SELECT COUNT(*) FROM grupo WHERE estado = 'ABIERTO') AS grupos_abiertos, "
              + "(SELECT COUNT(*) FROM matricula WHERE estado = 'MATRICULADO') AS matriculados, "
              + "(SELECT COUNT(*) FROM matricula WHERE estado = 'EN_ESPERA') AS en_espera, "
              + "(SELECT COALESCE(SUM(monto),0) FROM cuota WHERE estado = 'PAGADA') AS ingresos, "
              + "(SELECT COALESCE(SUM(q.monto),0) FROM cuota q JOIN matricula m ON m.id = q.id_matricula "
              + " WHERE q.estado IN ('PENDIENTE','VENCIDA') AND m.estado IN ('PREINSCRITO','MATRICULADO')) AS por_cobrar, "
              + "(SELECT COUNT(*) FROM bitacora_comando WHERE fecha >= CURRENT_DATE - 30) AS correos_30_dias").get();
        Respuesta r = Respuesta.tabla("estadísticas generales de UNEXO", List.of(t));
        grafico(r, "Matrículas por curso", Db.consultar(
                "SELECT cu.codigo AS curso, COUNT(m.id) AS n FROM curso cu JOIN cohorte co ON co.id_curso = cu.id "
              + "JOIN grupo g ON g.id_cohorte = co.id JOIN matricula m ON m.id_grupo = g.id GROUP BY cu.codigo ORDER BY n DESC LIMIT 10"),
                "curso", "n");
        grafico(r, "Comandos más usados (30 días)", Db.consultar(
                "SELECT comando, COUNT(*) AS n FROM bitacora_comando WHERE fecha >= CURRENT_DATE - 30 AND comando IS NOT NULL "
              + "GROUP BY comando ORDER BY n DESC LIMIT 10"), "comando", "n");
        return r;
    }

    // ---------- auxiliares ----------

    private static void rango(Contexto ctx) throws NegocioException {
        if (ctx.pFecha(1).before(ctx.pFecha(0))) {
            throw new NegocioException("rango de fechas inválido: fecha_ini debe ser menor o igual a fecha_fin.");
        }
    }

    private static void grafico(Respuesta r, String titulo, List<Map<String, Object>> filas, String etiqueta, String valor) {
        if (filas.isEmpty()) {
            return;
        }
        List<String> e = new ArrayList<>();
        List<Double> v = new ArrayList<>();
        for (Map<String, Object> f : filas) {
            e.add(String.valueOf(f.get(etiqueta)));
            Object x = f.get(valor);
            v.add(x == null ? 0 : ((Number) x).doubleValue());
        }
        r.agregarHtml(Html.barras(titulo, e, v), Html.barrasTexto(titulo, e, v));
    }
}
