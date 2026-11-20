package unexo.service;

import unexo.config.AppConfig;
import unexo.repository.Db;
import unexo.util.AppLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

/**
 * Cliente del Servidor SIS. Envía la matrícula en JSON:
 *   ALTA          -> POST {sis.url}/matriculas
 *   ACTUALIZACION -> PUT  {sis.url}/matriculas/{codigo}
 *   BAJA          -> PUT  {sis.url}/matriculas/{codigo}
 * Cada intento se registra en la tabla sincronizacion_sis.
 */
public class SisClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Envía la matrícula y devuelve el estado de la sincronización (ENVIADO, PENDIENTE o ERROR). */
    public String sincronizar(int idMatricula, String operacion, int intento) throws SQLException {
        Map<String, Object> m = Db.uno(
                "SELECT m.codigo, m.estado, m.tipo_pago, m.monto_total, m.fecha, p.ci, p.nombres, p.apellidos, u.correo, "
              + "g.codigo AS grupo, co.codigo AS cohorte, co.gestion, cu.codigo AS curso, cu.nombre AS curso_nombre "
              + "FROM matricula m JOIN cliente cl ON cl.id = m.id_cliente JOIN persona p ON p.ci = cl.ci_persona "
              + "LEFT JOIN usuario u ON u.ci_persona = p.ci JOIN grupo g ON g.id = m.id_grupo "
              + "JOIN cohorte co ON co.id = g.id_cohorte JOIN curso cu ON cu.id = co.id_curso WHERE m.id = ?", idMatricula)
                .orElseThrow(() -> new SQLException("Matrícula " + idMatricula + " inexistente"));

        String base = AppConfig.get("sis.url");
        String estado;
        Integer codigoHttp = null;
        String cuerpo;
        if (base.isEmpty()) {
            estado = "PENDIENTE";
            cuerpo = "sis.url no configurada; se reintentará con SINSIS.";
        } else {
            String json = json(m, operacion);
            String url = base.replaceAll("/+$", "") + "/matriculas" + ("ALTA".equals(operacion) ? "" : "/" + m.get("codigo"));
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=utf-8");
            String token = AppConfig.get("sis.token");
            if (!token.isEmpty()) {
                rb.header("Authorization", "Bearer " + token);
            }
            HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofString(json);
            rb = "ALTA".equals(operacion) ? rb.POST(body) : rb.PUT(body);
            try {
                HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                codigoHttp = resp.statusCode();
                cuerpo = resp.body();
                estado = codigoHttp >= 200 && codigoHttp < 300 ? "ENVIADO" : (codigoHttp >= 500 ? "PENDIENTE" : "ERROR");
            } catch (Exception e) {
                AppLogger.error("SIS no disponible para " + m.get("codigo"), e);
                estado = "PENDIENTE";
                cuerpo = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        if ("PENDIENTE".equals(estado) && intento >= AppConfig.getInt("sis.max.intentos", 3)) {
            estado = "ERROR";
        }
        Db.actualizar("INSERT INTO sincronizacion_sis (operacion, codigo_http, respuesta, intentos, estado, id_matricula) VALUES (?,?,?,?,?,?)",
                operacion, codigoHttp, cuerpo == null ? null : cuerpo.substring(0, Math.min(cuerpo.length(), 2000)),
                intento, estado, idMatricula);
        return estado;
    }

    public static String operacionPara(String estadoMatricula) {
        switch (estadoMatricula) {
            case "MATRICULADO":
                return "ALTA";
            case "RETIRADO":
            case "ANULADO":
                return "BAJA";
            default:
                return "ACTUALIZACION";
        }
    }

    private static String json(Map<String, Object> m, String operacion) {
        StringBuilder sb = new StringBuilder("{\"operacion\":").append(q(operacion));
        for (Map.Entry<String, Object> e : m.entrySet()) {
            sb.append(",").append(q(e.getKey())).append(":");
            Object v = e.getValue();
            sb.append(v instanceof Number ? v.toString() : q(v == null ? null : v.toString()));
        }
        return sb.append(",\"origen\":\"UNEXO-grupo06sa\"}").toString();
    }

    private static String q(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
