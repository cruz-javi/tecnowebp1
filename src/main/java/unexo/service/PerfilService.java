package unexo.service;

import unexo.parser.CommandCatalog;
import unexo.repository.Db;
import unexo.router.Contexto;
import unexo.router.NegocioException;
import unexo.util.Respuesta;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;

/** CU2 Gestión de Perfiles de Usuarios (permisos por comando). */
public class PerfilService {

    public Respuesta insprf(Contexto ctx) throws SQLException {
        int id = Db.insertarId("INSERT INTO perfil (nombre, descripcion) VALUES (?,?) RETURNING id",
                ctx.p(0).toUpperCase(Locale.ROOT), ctx.p(1));
        return Respuesta.ok("perfil " + ctx.p(0).toUpperCase(Locale.ROOT) + " registrado con id " + id + ".");
    }

    public Respuesta updprf(Contexto ctx) throws SQLException, NegocioException {
        noEsBase(ctx.pInt(0));
        int n = Db.actualizar("UPDATE perfil SET nombre=?, descripcion=? WHERE id=?",
                ctx.p(1).toUpperCase(Locale.ROOT), ctx.p(2), ctx.pInt(0));
        existe(n, ctx.pInt(0));
        return Respuesta.ok("perfil " + ctx.pInt(0) + " actualizado.");
    }

    public Respuesta delprf(Contexto ctx) throws SQLException, NegocioException {
        noEsBase(ctx.pInt(0));
        long usuarios = (Long) Db.uno("SELECT COUNT(*) AS n FROM usuario WHERE id_perfil = ? AND estado", ctx.pInt(0)).get().get("n");
        if (usuarios > 0) {
            throw new NegocioException("el perfil tiene " + usuarios + " usuario(s) activo(s); reasígnelos con ASGPRF antes de desactivarlo.");
        }
        existe(Db.actualizar("UPDATE perfil SET estado = FALSE WHERE id = ?", ctx.pInt(0)), ctx.pInt(0));
        return Respuesta.ok("perfil " + ctx.pInt(0) + " desactivado.");
    }

    public Respuesta lisprf(Contexto ctx) throws SQLException {
        return Respuesta.tabla("perfiles", Db.consultar(
                "SELECT pf.id, pf.nombre, pf.descripcion, pf.estado, COUNT(pp.id_permiso) AS permisos "
              + "FROM perfil pf LEFT JOIN perfil_permiso pp ON pp.id_perfil = pf.id GROUP BY pf.id ORDER BY pf.id"));
    }

    public Respuesta asgprm(Contexto ctx) throws SQLException, NegocioException {
        int idPerm = idPermiso(ctx.p(1));
        perfilActivo(ctx.pInt(0));
        int n = Db.actualizar("INSERT INTO perfil_permiso (id_perfil, id_permiso) VALUES (?,?) ON CONFLICT DO NOTHING",
                ctx.pInt(0), idPerm);
        return Respuesta.ok(n == 0 ? "el perfil ya tenía el permiso " + ctx.p(1).toUpperCase(Locale.ROOT) + "."
                : "permiso " + ctx.p(1).toUpperCase(Locale.ROOT) + " asignado al perfil " + ctx.pInt(0) + ".");
    }

    public Respuesta quiprm(Contexto ctx) throws SQLException, NegocioException {
        String cmd = ctx.p(1).toUpperCase(Locale.ROOT);
        if (ctx.pInt(0) == ctx.usuario().idPerfil() && ("QUIPRM".equals(cmd) || "ASGPRM".equals(cmd))) {
            throw new NegocioException("no puede quitarse a sí mismo la administración de permisos.");
        }
        int n = Db.actualizar("DELETE FROM perfil_permiso WHERE id_perfil = ? AND id_permiso = ?", ctx.pInt(0), idPermiso(cmd));
        if (n == 0) {
            throw new NegocioException("el perfil " + ctx.pInt(0) + " no tiene el permiso " + cmd + ".");
        }
        return Respuesta.ok("permiso " + cmd + " retirado del perfil " + ctx.pInt(0) + ".");
    }

    public Respuesta lisprm(Contexto ctx) throws SQLException, NegocioException {
        perfilActivo(ctx.pInt(0));
        return Respuesta.tabla("permisos del perfil " + ctx.pInt(0), Db.consultar(
                "SELECT pm.comando, pm.descripcion, pp.fecha_asignacion FROM perfil_permiso pp "
              + "JOIN permiso pm ON pm.id = pp.id_permiso WHERE pp.id_perfil = ? ORDER BY pm.comando", ctx.pInt(0)));
    }

    public Respuesta asgprf(Contexto ctx) throws SQLException, NegocioException {
        perfilActivo(ctx.pInt(1));
        int n = Db.actualizar("UPDATE usuario SET id_perfil = ? WHERE ci_persona = ?", ctx.pInt(1), ctx.p(0));
        if (n == 0) {
            throw new NegocioException("no existe un usuario con CI " + ctx.p(0) + ".");
        }
        return Respuesta.ok("perfil " + ctx.pInt(1) + " asignado al usuario " + ctx.p(0) + ".");
    }

    // ---------- auxiliares ----------

    private static int idPermiso(String comando) throws SQLException, NegocioException {
        String cmd = comando.toUpperCase(Locale.ROOT);
        if (CommandCatalog.buscar(cmd) == null) {
            throw new NegocioException("el comando " + cmd + " no existe.");
        }
        Map<String, Object> p = Db.uno("SELECT id FROM permiso WHERE comando = ?", cmd)
                .orElseThrow(() -> new NegocioException("el permiso " + cmd + " no está registrado."));
        return (Integer) p.get("id");
    }

    private static void perfilActivo(int id) throws SQLException, NegocioException {
        if (Db.uno("SELECT 1 FROM perfil WHERE id = ? AND estado", id).isEmpty()) {
            throw new NegocioException("no existe un perfil activo con id " + id + ".");
        }
    }

    private static void noEsBase(int id) throws SQLException, NegocioException {
        if (Db.uno("SELECT 1 FROM perfil WHERE id = ? AND nombre IN ('PROPIETARIO','CLIENTE')", id).isPresent()) {
            throw new NegocioException("los perfiles base PROPIETARIO y CLIENTE no se pueden modificar ni desactivar.");
        }
    }

    private static void existe(int filas, int id) throws NegocioException {
        if (filas == 0) {
            throw new NegocioException("no existe el perfil " + id + ".");
        }
    }
}
