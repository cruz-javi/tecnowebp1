package unexo.service;

import unexo.repository.Db;
import unexo.router.Contexto;
import unexo.router.NegocioException;
import unexo.util.Respuesta;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** CU3 Gestión de Catálogo Académico y Oferta Comercial. */
public class CatalogoService {

    private static final String SELECT_CURSO =
            "SELECT c.codigo, c.nombre, cat.nombre AS categoria, c.carga_horaria AS horas, c.requisitos, c.estado "
          + "FROM curso c JOIN categoria cat ON cat.id = c.id_categoria ";

    static final String OFERTA_VIGENTE =
            "SELECT o.* FROM oferta_comercial o WHERE o.id_curso = ? AND o.estado "
          + "AND CURRENT_DATE BETWEEN o.fecha_inicio AND o.fecha_fin ORDER BY o.fecha_inicio DESC LIMIT 1";

    public Respuesta inscat(Contexto ctx) throws SQLException {
        int id = Db.insertarId("INSERT INTO categoria (nombre, descripcion) VALUES (?,?) RETURNING id", ctx.p(0), ctx.p(1));
        return Respuesta.ok("categoría \"" + ctx.p(0) + "\" registrada con id " + id + ".");
    }

    public Respuesta liscat(Contexto ctx) throws SQLException {
        return Respuesta.tabla("categorías", Db.consultar(
                "SELECT cat.id, cat.nombre, cat.descripcion, COUNT(c.id) AS cursos FROM categoria cat "
              + "LEFT JOIN curso c ON c.id_categoria = cat.id AND c.estado WHERE cat.estado GROUP BY cat.id ORDER BY cat.nombre"));
    }

    public Respuesta inscur(Contexto ctx) throws SQLException, NegocioException {
        validarCurso(ctx);
        Db.actualizar("INSERT INTO curso (codigo, nombre, descripcion, carga_horaria, requisitos, id_categoria) VALUES (?,?,?,?,?,?)",
                ctx.p(0), ctx.p(1), ctx.p(2), ctx.pInt(3), ctx.p(4), ctx.pInt(5));
        return Respuesta.ok("curso " + ctx.p(0) + " \"" + ctx.p(1) + "\" registrado correctamente.");
    }

    public Respuesta updcur(Contexto ctx) throws SQLException, NegocioException {
        validarCurso(ctx);
        int n = Db.actualizar("UPDATE curso SET nombre=?, descripcion=?, carga_horaria=?, requisitos=?, id_categoria=? WHERE codigo=?",
                ctx.p(1), ctx.p(2), ctx.pInt(3), ctx.p(4), ctx.pInt(5), ctx.p(0));
        if (n == 0) {
            throw new NegocioException("no existe el curso " + ctx.p(0) + ".");
        }
        return Respuesta.ok("curso " + ctx.p(0) + " actualizado.");
    }

    public Respuesta delcur(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> curso = curso(ctx.p(0));
        long activas = (Long) Db.uno("SELECT COUNT(*) AS n FROM cohorte WHERE id_curso = ? AND estado IN ('PLANIFICADA','EN_CURSO')",
                curso.get("id")).get().get("n");
        if (activas > 0) {
            throw new NegocioException("el curso tiene " + activas + " cohorte(s) planificada(s) o en curso.");
        }
        Db.actualizar("UPDATE curso SET estado = FALSE WHERE id = ?", curso.get("id"));
        return Respuesta.ok("curso " + ctx.p(0) + " desactivado.");
    }

    public Respuesta liscur(Contexto ctx) throws SQLException {
        List<Map<String, Object>> filas = "*".equals(ctx.p(0))
                ? Db.consultar(SELECT_CURSO + "WHERE c.estado ORDER BY c.codigo")
                : Db.consultar(SELECT_CURSO + "WHERE c.estado AND c.id_categoria = ? ORDER BY c.codigo", ctx.pInt(0));
        return Respuesta.tabla("cursos", filas);
    }

    public Respuesta getcur(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> curso = curso(ctx.p(0));
        Respuesta r = Respuesta.tabla("curso " + ctx.p(0), Db.consultar(
                "SELECT c.codigo, c.nombre, c.descripcion, c.carga_horaria AS horas, c.requisitos, cat.nombre AS categoria "
              + "FROM curso c JOIN categoria cat ON cat.id = c.id_categoria WHERE c.id = ?", curso.get("id")));
        List<Map<String, Object>> oferta = Db.consultar(
                "SELECT id, precio, moneda, descuento_contado AS \"desc_contado_%\", max_cuotas, fecha_inicio, fecha_fin "
              + "FROM (" + OFERTA_VIGENTE + ") o", curso.get("id"));
        r.agregarTexto(oferta.isEmpty() ? "El curso no tiene una oferta vigente." : "Oferta vigente:");
        if (!oferta.isEmpty()) {
            r.agregarTabla(oferta);
        }
        return r;
    }

    public Respuesta insofe(Contexto ctx) throws SQLException, NegocioException {
        Map<String, Object> curso = curso(ctx.p(0));
        validarOferta(ctx.pDec(1), ctx.pDec(3), ctx.pInt(4));
        if (ctx.pFecha(6).before(ctx.pFecha(5))) {
            throw new NegocioException("fecha_fin debe ser mayor o igual a fecha_inicio.");
        }
        int id = Db.insertarId("INSERT INTO oferta_comercial (precio, moneda, descuento_contado, max_cuotas, fecha_inicio, fecha_fin, id_curso) "
                        + "VALUES (?,?,?,?,?,?,?) RETURNING id",
                ctx.pDec(1), ctx.p(2), ctx.pDec(3), ctx.pInt(4), ctx.pFecha(5), ctx.pFecha(6), curso.get("id"));
        return Respuesta.ok("oferta " + id + " registrada para el curso " + ctx.p(0) + ": " + ctx.p(1) + " " + ctx.p(2)
                + ", descuento al contado " + ctx.p(3) + "%, hasta " + ctx.p(4) + " cuota(s).");
    }

    public Respuesta updofe(Contexto ctx) throws SQLException, NegocioException {
        validarOferta(ctx.pDec(1), ctx.pDec(2), ctx.pInt(3));
        int n = Db.actualizar("UPDATE oferta_comercial SET precio=?, descuento_contado=?, max_cuotas=?, fecha_fin=? "
                        + "WHERE id=? AND fecha_inicio <= ?",
                ctx.pDec(1), ctx.pDec(2), ctx.pInt(3), ctx.pFecha(4), ctx.pInt(0), ctx.pFecha(4));
        if (n == 0) {
            throw new NegocioException("no existe la oferta " + ctx.p(0) + " o la fecha_fin es anterior a su inicio.");
        }
        return Respuesta.ok("oferta " + ctx.p(0) + " actualizada (las matrículas ya registradas conservan su monto).");
    }

    public Respuesta lisofe(Contexto ctx) throws SQLException {
        return Respuesta.tabla("ofertas vigentes", Db.consultar(
                "SELECT o.id, c.codigo, c.nombre AS curso, o.precio, o.moneda, o.descuento_contado AS \"desc_contado_%\", "
              + "o.max_cuotas, o.fecha_inicio, o.fecha_fin FROM oferta_comercial o JOIN curso c ON c.id = o.id_curso "
              + "WHERE o.estado AND c.estado AND CURRENT_DATE BETWEEN o.fecha_inicio AND o.fecha_fin ORDER BY c.codigo"));
    }

    // ---------- auxiliares ----------

    static Map<String, Object> curso(String codigo) throws SQLException, NegocioException {
        return Db.uno("SELECT * FROM curso WHERE codigo = ?", codigo)
                .orElseThrow(() -> new NegocioException("no existe el curso " + codigo + "."));
    }

    private static void validarCurso(Contexto ctx) throws SQLException, NegocioException {
        if (ctx.pInt(3) <= 0) {
            throw new NegocioException("la carga horaria debe ser mayor a 0.");
        }
        if (Db.uno("SELECT 1 FROM categoria WHERE id = ? AND estado", ctx.pInt(5)).isEmpty()) {
            throw new NegocioException("no existe la categoría " + ctx.p(5) + ". Regístrela con INSCAT.");
        }
    }

    private static void validarOferta(BigDecimal precio, BigDecimal descuento, int cuotas) throws NegocioException {
        if (precio.signum() <= 0) {
            throw new NegocioException("el precio debe ser mayor a 0.");
        }
        if (descuento.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new NegocioException("el descuento al contado debe estar entre 0 y 100.");
        }
        if (cuotas < 1 || cuotas > 12) {
            throw new NegocioException("max_cuotas debe estar entre 1 y 12.");
        }
    }
}
