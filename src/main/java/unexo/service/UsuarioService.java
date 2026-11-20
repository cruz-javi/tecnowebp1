package unexo.service;

import unexo.config.AppConfig;
import unexo.mail.MailMessage;
import unexo.repository.Db;
import unexo.router.Contexto;
import unexo.router.NegocioException;
import unexo.util.Passwords;
import unexo.util.Respuesta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** CU1 Gestión de Usuarios (Propietario, Clientes) – Fotos. */
public class UsuarioService {

    private static final String SELECT_USUARIO =
            "SELECT p.ci, p.nombres, p.apellidos, p.telefono, p.direccion, p.genero, p.fecha_nacimiento, "
          + "u.correo, pf.nombre AS perfil, u.foto_url, u.estado "
          + "FROM usuario u JOIN persona p ON p.ci = u.ci_persona JOIN perfil pf ON pf.id = u.id_perfil ";

    /** REGCLI: autorregistro; el correo es el del remitente. */
    public Respuesta regcli(Contexto ctx) throws SQLException, NegocioException {
        if (ctx.usuario() != null) {
            throw new NegocioException("el correo " + ctx.remitente() + " ya está registrado.");
        }
        String ci = ctx.p(0);
        Db.transaccion(c -> {
            insertarPersona(c, ci, ctx.p(1), ctx.p(2), ctx.p(3), null, ctx.p(4), ctx.p(5));
            int idPerfil = idPerfil(c, "CLIENTE");
            Db.actualizar(c, "INSERT INTO usuario (correo, password_hash, ci_persona, id_perfil) VALUES (?,?,?,?)",
                    ctx.remitente(), Passwords.hash(ctx.p(6)), ci, idPerfil);
            Db.actualizar(c, "INSERT INTO cliente (ci_persona) VALUES (?)", ci);
            return null;
        });
        return Respuesta.ok("cliente " + ctx.p(1) + " " + ctx.p(2) + " registrado con el correo " + ctx.remitente() + ".")
                .agregarTexto("Ya puede consultar la oferta con LISOFE[\"*\"] y matricularse con INSMAT.");
    }

    /** INSUSU */
    public Respuesta insusu(Contexto ctx) throws SQLException {
        String ci = ctx.p(0);
        String perfil = ctx.p(9);
        Db.transaccion(c -> {
            insertarPersona(c, ci, ctx.p(1), ctx.p(2), ctx.p(3), ctx.p(4), ctx.p(5), ctx.p(6));
            Db.actualizar(c, "INSERT INTO usuario (correo, password_hash, ci_persona, id_perfil) VALUES (?,?,?,?)",
                    ctx.p(7), Passwords.hash(ctx.p(8)), ci, idPerfil(c, perfil));
            if ("CLIENTE".equals(perfil)) {
                Db.actualizar(c, "INSERT INTO cliente (ci_persona) VALUES (?)", ci);
            } else {
                Db.actualizar(c, "INSERT INTO propietario (ci_persona, fecha_inicio) VALUES (?, CURRENT_DATE)", ci);
            }
            return null;
        });
        return Respuesta.ok("usuario " + ctx.p(1) + " " + ctx.p(2) + " (" + perfil + ") registrado correctamente.");
    }

    /** UPDUSU */
    public Respuesta updusu(Contexto ctx) throws SQLException, NegocioException {
        String ci = ctx.p(0);
        ctx.exigirPropio(ci);
        int n = Db.transaccion(c -> {
            int filas = Db.actualizar(c, "UPDATE persona SET nombres=?, apellidos=?, telefono=?, direccion=?, genero=?, fecha_nacimiento=? WHERE ci=?",
                    ctx.p(1), ctx.p(2), ctx.p(3), ctx.p(4), ctx.p(5), ctx.pFecha(6), ci);
            Db.actualizar(c, "UPDATE usuario SET correo=? WHERE ci_persona=?", ctx.p(7), ci);
            return filas;
        });
        if (n == 0) {
            throw new NegocioException("no existe una persona con CI " + ci + ".");
        }
        return Respuesta.ok("usuario con CI " + ci + " actualizado correctamente.");
    }

    /** DELUSU: baja lógica */
    public Respuesta delusu(Contexto ctx) throws SQLException, NegocioException {
        String ci = ctx.p(0);
        if (ctx.usuario().ci().equalsIgnoreCase(ci)) {
            throw new NegocioException("no puede darse de baja a sí mismo.");
        }
        int n = Db.actualizar("UPDATE usuario SET estado = FALSE WHERE ci_persona = ? AND estado", ci);
        if (n == 0) {
            throw new NegocioException("no existe un usuario activo con CI " + ci + ".");
        }
        return Respuesta.ok("usuario con CI " + ci + " dado de baja (baja lógica).");
    }

    /** LISUSU */
    public Respuesta lisusu(Contexto ctx) throws SQLException {
        String filtro = ctx.p(0);
        List<Map<String, Object>> filas = "*".equals(filtro)
                ? Db.consultar(SELECT_USUARIO + "ORDER BY p.apellidos, p.nombres")
                : Db.consultar(SELECT_USUARIO + "WHERE pf.nombre = ? ORDER BY p.apellidos, p.nombres", filtro);
        return Respuesta.tabla("listado de usuarios", filas);
    }

    /** GETUSU */
    public Respuesta getusu(Contexto ctx) throws SQLException, NegocioException {
        String ci = ctx.p(0);
        ctx.exigirPropio(ci);
        Map<String, Object> u = Db.uno(SELECT_USUARIO + "WHERE p.ci = ?", ci)
                .orElseThrow(() -> new NegocioException("no existe un usuario con CI " + ci + "."));
        Respuesta r = Respuesta.tabla("datos del usuario " + ci, List.of(u));
        Db.uno("SELECT tipo_cliente, institucion, profesion FROM cliente WHERE ci_persona = ?", ci)
                .ifPresent(cl -> r.agregarTabla(List.of(cl)));
        return r;
    }

    /** FOTUSU: guarda la imagen adjunta en el servidor web y actualiza foto_url. */
    public Respuesta fotusu(Contexto ctx) throws SQLException, NegocioException {
        String ci = ctx.p(0);
        ctx.exigirPropio(ci);
        MailMessage.Adjunto img = ctx.adjuntos().stream()
                .filter(a -> extension(a) != null).findFirst()
                .orElseThrow(() -> new NegocioException("adjunte una imagen JPG o PNG al correo."));
        if (img.datos().length > 2 * 1024 * 1024) {
            throw new NegocioException("la imagen supera los 2 MB.");
        }
        if (Db.uno("SELECT 1 FROM usuario WHERE ci_persona = ?", ci).isEmpty()) {
            throw new NegocioException("no existe un usuario con CI " + ci + ".");
        }
        String archivo = ci.replaceAll("[^A-Za-z0-9_-]", "") + "." + extension(img);
        try {
            Path dir = Path.of(AppConfig.get("fotos.dir", "fotos"));
            Files.createDirectories(dir);
            Files.write(dir.resolve(archivo), img.datos());
        } catch (IOException e) {
            throw new NegocioException("no se pudo guardar la foto en el servidor: " + e.getMessage());
        }
        String url = AppConfig.get("fotos.url", "https://www.tecnoweb.org.bo/inf513/grupo06sa/fotos") + "/" + archivo;
        Db.actualizar("UPDATE usuario SET foto_url = ? WHERE ci_persona = ?", url, ci);
        return Respuesta.ok("foto del usuario " + ci + " actualizada: " + url);
    }

    /** INSCLI: datos complementarios del cliente (crea o actualiza). */
    public Respuesta inscli(Contexto ctx) throws SQLException, NegocioException {
        String ci = ctx.p(0);
        ctx.exigirPropio(ci);
        if (Db.uno("SELECT 1 FROM persona WHERE ci = ?", ci).isEmpty()) {
            throw new NegocioException("no existe una persona con CI " + ci + ".");
        }
        Db.actualizar("INSERT INTO cliente (ci_persona, tipo_cliente, institucion, profesion) VALUES (?,?,?,?) "
                + "ON CONFLICT (ci_persona) DO UPDATE SET tipo_cliente = EXCLUDED.tipo_cliente, "
                + "institucion = EXCLUDED.institucion, profesion = EXCLUDED.profesion",
                ci, ctx.p(1), ctx.p(2), ctx.p(3));
        return Respuesta.ok("datos de cliente del CI " + ci + " guardados.");
    }

    // ---------- auxiliares ----------

    private static void insertarPersona(Connection c, String ci, String nombres, String apellidos, String telefono,
                                        String direccion, String genero, String fechaNac) throws SQLException {
        Db.actualizar(c, "INSERT INTO persona (ci, nombres, apellidos, telefono, direccion, genero, fecha_nacimiento) VALUES (?,?,?,?,?,?,?)",
                ci, nombres, apellidos, telefono, direccion, genero, java.sql.Date.valueOf(fechaNac));
    }

    static int idPerfil(Connection c, String nombre) throws SQLException {
        Optional<Map<String, Object>> p = Db.uno(c, "SELECT id FROM perfil WHERE nombre = ? AND estado", nombre);
        if (p.isEmpty()) {
            throw new SQLException("El perfil " + nombre + " no existe o está inactivo.");
        }
        return (Integer) p.get().get("id");
    }

    private static String extension(MailMessage.Adjunto a) {
        String ct = a.contentType() == null ? "" : a.contentType().toLowerCase(Locale.ROOT);
        String n = a.nombre() == null ? "" : a.nombre().toLowerCase(Locale.ROOT);
        if (ct.contains("png") || n.endsWith(".png")) {
            return "png";
        }
        if (ct.contains("jpeg") || ct.contains("jpg") || n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            return "jpg";
        }
        return null;
    }
}
