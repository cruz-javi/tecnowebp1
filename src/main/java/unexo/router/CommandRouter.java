package unexo.router;

import unexo.config.AppConfig;
import unexo.mail.MailMessage;
import unexo.parser.CommandCatalog;
import unexo.parser.CommandSpec;
import unexo.parser.CommandValidator;
import unexo.parser.Comando;
import unexo.parser.SintaxisException;
import unexo.parser.SubjectParser;
import unexo.repository.Db;
import unexo.service.CatalogoService;
import unexo.service.CohorteGrupoService;
import unexo.service.EstadoMatriculaService;
import unexo.service.LogisticaService;
import unexo.service.MatriculaService;
import unexo.service.PerfilService;
import unexo.service.ReporteService;
import unexo.service.UsuarioService;
import unexo.util.AppLogger;
import unexo.util.Passwords;
import unexo.util.Respuesta;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Despachador de comandos: parsea el asunto, valida el comando, verifica el permiso del
 * remitente según su perfil, ejecuta el servicio y convierte cualquier fallo en "Error: ...".
 */
public class CommandRouter {

    private static final long SESION_MS = 30L * 60L * 1000L;

    private final Map<String, Handler> handlers = new HashMap<>();
    private final Map<String, Long> sesiones = new ConcurrentHashMap<>();
    private final EstadoMatriculaService estados = new EstadoMatriculaService();

    /** Resultado del procesamiento, con el comando reconocido (o null). */
    public record Resultado(String comando, Respuesta respuesta) {
    }

    public CommandRouter() {
        UsuarioService usu = new UsuarioService();
        PerfilService prf = new PerfilService();
        CatalogoService cat = new CatalogoService();
        CohorteGrupoService coh = new CohorteGrupoService();
        LogisticaService log = new LogisticaService();
        MatriculaService mat = new MatriculaService(estados);
        ReporteService rep = new ReporteService();

        handlers.put("HELP", this::help);
        handlers.put("AUTH", this::auth);
        // CU1
        handlers.put("REGCLI", usu::regcli);
        handlers.put("INSUSU", usu::insusu);
        handlers.put("UPDUSU", usu::updusu);
        handlers.put("DELUSU", usu::delusu);
        handlers.put("LISUSU", usu::lisusu);
        handlers.put("GETUSU", usu::getusu);
        handlers.put("FOTUSU", usu::fotusu);
        handlers.put("INSCLI", usu::inscli);
        // CU2
        handlers.put("INSPRF", prf::insprf);
        handlers.put("UPDPRF", prf::updprf);
        handlers.put("DELPRF", prf::delprf);
        handlers.put("LISPRF", prf::lisprf);
        handlers.put("ASGPRM", prf::asgprm);
        handlers.put("QUIPRM", prf::quiprm);
        handlers.put("LISPRM", prf::lisprm);
        handlers.put("ASGPRF", prf::asgprf);
        // CU3
        handlers.put("INSCAT", cat::inscat);
        handlers.put("LISCAT", cat::liscat);
        handlers.put("INSCUR", cat::inscur);
        handlers.put("UPDCUR", cat::updcur);
        handlers.put("DELCUR", cat::delcur);
        handlers.put("LISCUR", cat::liscur);
        handlers.put("GETCUR", cat::getcur);
        handlers.put("INSOFE", cat::insofe);
        handlers.put("UPDOFE", cat::updofe);
        handlers.put("LISOFE", cat::lisofe);
        // CU4
        handlers.put("INSCOH", coh::inscoh);
        handlers.put("UPDCOH", coh::updcoh);
        handlers.put("LISCOH", coh::liscoh);
        handlers.put("INSINS", coh::insins);
        handlers.put("LISINS", coh::lisins);
        handlers.put("INSGRU", coh::insgru);
        handlers.put("ABRGRU", coh::abrgru);
        handlers.put("CERGRU", coh::cergru);
        handlers.put("CANGRU", coh::cangru);
        handlers.put("LISGRU", coh::lisgru);
        // CU5
        handlers.put("INSAUL", log::insaul);
        handlers.put("LISAUL", log::lisaul);
        handlers.put("INSHOR", log::inshor);
        handlers.put("UPDHOR", log::updhor);
        handlers.put("DELHOR", log::delhor);
        handlers.put("LISHOR", log::lishor);
        // CU6
        handlers.put("INSMAT", mat::insmat);
        handlers.put("GETMAT", mat::getmat);
        handlers.put("LISMAT", mat::lismat);
        handlers.put("CAMGRU", mat::camgru);
        handlers.put("PAGCUO", mat::pagcuo);
        handlers.put("LISCUO", mat::liscuo);
        // CU7
        handlers.put("ESTMAT", estados::estmat);
        handlers.put("UPDEST", estados::updest);
        handlers.put("HISEST", estados::hisest);
        handlers.put("SINSIS", estados::sinsis);
        handlers.put("LISSIS", estados::lissis);
        // CU8
        handlers.put("REPMAT", rep::repmat);
        handlers.put("REPING", rep::reping);
        handlers.put("REPGRU", rep::repgru);
        handlers.put("REPCUR", rep::repcur);
        handlers.put("ESTGEN", rep::estgen);

        for (CommandSpec s : CommandCatalog.todos()) {
            if (!handlers.containsKey(s.nombre())) {
                throw new IllegalStateException("Comando sin implementación: " + s.nombre());
            }
        }
    }

    public EstadoMatriculaService estados() {
        return estados;
    }

    public Resultado procesar(MailMessage msg) {
        String comando = null;
        try {
            Comando cmd = SubjectParser.parsear(msg.subject());
            comando = cmd.nombre();
            CommandSpec spec = CommandValidator.spec(cmd);
            Contexto.Usuario usuario = buscarUsuario(msg.from());

            autorizar(spec, usuario, msg.from());
            List<String> valores = CommandValidator.validar(cmd, spec);
            if (spec.requiereAdjunto() && msg.adjuntos().isEmpty()) {
                throw new SintaxisException(spec.nombre() + " requiere una imagen adjunta (JPG o PNG).");
            }
            Contexto ctx = new Contexto(msg.from(), usuario, valores, msg.adjuntos());
            return new Resultado(comando, handlers.get(spec.nombre()).ejecutar(ctx));
        } catch (SintaxisException | NegocioException e) {
            return new Resultado(comando, Respuesta.error(e.getMessage()));
        } catch (SQLException e) {
            if ("UNX01".equals(e.getSQLState())) {
                return new Resultado(comando, Respuesta.error(e.getMessage()));
            }
            AppLogger.error("SQL en " + comando + " de " + msg.from(), e);
            return new Resultado(comando, Respuesta.error(traducir(e)));
        } catch (RuntimeException e) {
            AppLogger.error("Excepción no controlada en " + comando + " de " + msg.from(), e);
            return new Resultado(comando, Respuesta.error("error interno al procesar " + comando + ": " + e.getMessage()));
        }
    }

    // ---------- autorización ----------

    private void autorizar(CommandSpec spec, Contexto.Usuario usuario, String remitente)
            throws SQLException, NegocioException {
        if (usuario == null) {
            if (!spec.perfiles().contains(CommandCatalog.PUB)) {
                throw new NegocioException("el remitente " + remitente + " no está registrado. Regístrese con "
                        + CommandCatalog.buscar("REGCLI").firma());
            }
            return;
        }
        if ("HELP".equals(spec.nombre()) || "AUTH".equals(spec.nombre())) {
            return;
        }
        if (spec.perfiles().equals(java.util.Set.of(CommandCatalog.PUB))) {
            throw new NegocioException("usted ya está registrado; " + spec.nombre() + " es solo para nuevos clientes.");
        }
        boolean permitido = Db.uno("SELECT 1 FROM perfil_permiso pp JOIN permiso pm ON pm.id = pp.id_permiso "
                + "WHERE pp.id_perfil = ? AND pm.comando = ?", usuario.idPerfil(), spec.nombre()).isPresent();
        if (!permitido) {
            throw new NegocioException("su perfil " + usuario.perfil() + " no tiene permiso para " + spec.nombre() + ".");
        }
        if (CommandCatalog.P.equals(usuario.perfil()) && AppConfig.getBool("auth.requerida", true)) {
            Long inicio = sesiones.get(usuario.correo());
            if (inicio == null || System.currentTimeMillis() - inicio > SESION_MS) {
                sesiones.remove(usuario.correo());
                throw new NegocioException("autenticación requerida. Envíe AUTH[\"su_contraseña\"]; la sesión dura 30 minutos.");
            }
        }
    }

    private Contexto.Usuario buscarUsuario(String correo) throws SQLException {
        Optional<Map<String, Object>> u = Db.uno(
                "SELECT u.id, u.ci_persona, u.correo, u.id_perfil, pf.nombre AS perfil, cl.id AS id_cliente "
              + "FROM usuario u JOIN perfil pf ON pf.id = u.id_perfil LEFT JOIN cliente cl ON cl.ci_persona = u.ci_persona "
              + "WHERE lower(u.correo) = lower(?) AND u.estado", correo);
        return u.map(r -> new Contexto.Usuario((Integer) r.get("id"), (String) r.get("ci_persona"), (String) r.get("correo"),
                (Integer) r.get("id_perfil"), (String) r.get("perfil"), (Integer) r.get("id_cliente"))).orElse(null);
    }

    // ---------- comandos generales ----------

    private Respuesta auth(Contexto ctx) throws SQLException, NegocioException {
        String hash = (String) Db.uno("SELECT password_hash FROM usuario WHERE id = ?", ctx.usuario().id()).get().get("password_hash");
        if (!Passwords.verificar(ctx.p(0), hash)) {
            sesiones.remove(ctx.usuario().correo());
            throw new NegocioException("contraseña incorrecta.");
        }
        sesiones.put(ctx.usuario().correo(), System.currentTimeMillis());
        return Respuesta.ok("sesión iniciada para " + ctx.usuario().correo() + " por 30 minutos.");
    }

    private Respuesta help(Contexto ctx) throws SQLException {
        List<Map<String, Object>> filas = new ArrayList<>();
        java.util.Set<String> permitidos = new java.util.HashSet<>();
        if (ctx.usuario() != null) {
            for (Map<String, Object> r : Db.consultar("SELECT pm.comando FROM perfil_permiso pp JOIN permiso pm ON pm.id = pp.id_permiso "
                    + "WHERE pp.id_perfil = ?", ctx.usuario().idPerfil())) {
                permitidos.add((String) r.get("comando"));
            }
        }
        for (CommandSpec s : CommandCatalog.todos()) {
            boolean ok = ctx.usuario() == null ? s.perfiles().contains(CommandCatalog.PUB)
                    : permitidos.contains(s.nombre()) || "HELP".equals(s.nombre()) || "AUTH".equals(s.nombre());
            if (ok) {
                Map<String, Object> f = new java.util.LinkedHashMap<>();
                f.put("CU", s.cu());
                f.put("comando", s.firma());
                f.put("descripción", s.descripcion());
                filas.add(f);
            }
        }
        Respuesta r = Respuesta.tabla("comandos disponibles para " + (ctx.usuario() == null ? "nuevos usuarios" : "el perfil " + ctx.usuario().perfil()), filas);
        r.agregarTexto("Formato: escriba el comando en el ASUNTO del correo, por ejemplo LISCUR[\"*\"]. "
                + "Use comillas dobles para cada parámetro y \\\" para incluir comillas dentro de un texto. Fechas AAAA-MM-DD, horas HH:MM, \"-\" = vacío.");
        return r;
    }

    // ---------- errores SQL ----------

    private static String traducir(SQLException e) {
        String estado = e.getSQLState() == null ? "" : e.getSQLState();
        String detalle = e.getMessage() == null ? "" : e.getMessage();
        int k = detalle.indexOf("Detail:");
        if (k < 0) {
            k = detalle.indexOf("Detalle:");
        }
        String det = k >= 0 ? detalle.substring(k).replace("Detail:", "").replace("Detalle:", "").trim() : "";
        switch (estado) {
            case "23505":
                return "registro duplicado (violación de unicidad). " + det;
            case "23503":
                return "referencia inexistente (violación de llave foránea). " + det;
            case "23514":
                return "un valor no cumple las reglas del dominio (restricción CHECK). " + det;
            case "23502":
                return "falta un valor obligatorio. " + det;
            case "22001":
                return "un texto supera la longitud permitida.";
            default:
                if (estado.startsWith("08")) {
                    return "no se pudo conectar con la base de datos. Intente nuevamente en unos minutos.";
                }
                return "fallo en la base de datos (" + estado + "): " + detalle.split("\n")[0];
        }
    }
}
