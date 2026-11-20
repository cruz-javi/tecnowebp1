package unexo;

import unexo.config.AppConfig;
import unexo.mail.MailDaemon;
import unexo.mail.MailMessage;
import unexo.parser.CommandCatalog;
import unexo.parser.CommandSpec;
import unexo.repository.Db;
import unexo.router.CommandRouter;
import unexo.util.Passwords;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Punto de entrada.
 *   java -jar unexo.jar               -> inicia el MailDaemon
 *   java -cp unexo.jar unexo.App init -> crea las tablas, permisos y el propietario semilla
 *   java -cp unexo.jar unexo.App simular correo@x.com 'LISCUR["*"]' -> prueba un comando sin correo
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) throws Exception {
        String modo = args.length == 0 ? "daemon" : args[0];
        switch (modo) {
            case "init":
                inicializar();
                break;
            case "simular":
                if (args.length < 3) {
                    System.out.println("Uso: App simular <correo_remitente> '<ASUNTO>'");
                    return;
                }
                CommandRouter.Resultado r = new CommandRouter().procesar(new MailMessage(args[1].toLowerCase(), args[2], List.of()));
                System.out.println(r.respuesta().getTexto());
                break;
            case "guion":
                // Ejecuta un archivo con líneas "correo | ASUNTO" usando el mismo router (útil para demos).
                CommandRouter router = new CommandRouter();
                for (String linea : java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[1]), StandardCharsets.UTF_8)) {
                    if (linea.isBlank() || linea.startsWith("#")) {
                        continue;
                    }
                    int k = linea.indexOf('|');
                    String correo = linea.substring(0, k).trim().toLowerCase();
                    String asunto = linea.substring(k + 1).trim();
                    System.out.println(">>> " + correo + " | " + asunto);
                    System.out.println(router.procesar(new MailMessage(correo, asunto, List.of())).respuesta().getTexto()
                            .replaceAll("(?s)\n--\nUNEXO.*", ""));
                }
                break;
            default:
                MailDaemon.main(new String[0]);
        }
    }

    /** Crea el esquema (si no existe), registra los permisos de todos los comandos y el propietario semilla. */
    static void inicializar() throws SQLException, IOException {
        boolean existe = Db.uno("SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'persona'")
                .isPresent();
        if (!existe) {
            String sql;
            try (InputStream in = App.class.getResourceAsStream("/schema.sql")) {
                if (in == null) {
                    throw new IOException("No se encontró schema.sql en el classpath");
                }
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            try (Connection c = Db.conectar(); Statement st = c.createStatement()) {
                st.execute(sql);
            }
            System.out.println("Esquema creado (20 tablas).");
        } else {
            System.out.println("El esquema ya existe; solo se actualizan permisos.");
        }

        Db.transaccion(c -> {
            for (String[] pf : new String[][]{{"PROPIETARIO", "Acceso total a los comandos del sistema"},
                    {"CLIENTE", "Consulta de oferta, matrícula propia y estado"}}) {
                Db.actualizar(c, "INSERT INTO perfil (nombre, descripcion) SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM perfil WHERE nombre = ?)",
                        pf[0], pf[1], pf[0]);
            }
            int idProp = (Integer) Db.uno(c, "SELECT id FROM perfil WHERE nombre = 'PROPIETARIO'").get().get("id");
            int idCli = (Integer) Db.uno(c, "SELECT id FROM perfil WHERE nombre = 'CLIENTE'").get().get("id");
            for (CommandSpec s : CommandCatalog.todos()) {
                if (Db.actualizar(c, "UPDATE permiso SET descripcion = ? WHERE comando = ?", s.descripcion(), s.nombre()) == 0) {
                    Db.actualizar(c, "INSERT INTO permiso (comando, descripcion) VALUES (?,?)", s.nombre(), s.descripcion());
                }
                int idPerm = (Integer) Db.uno(c, "SELECT id FROM permiso WHERE comando = ?", s.nombre()).get().get("id");
                if (s.perfiles().contains(CommandCatalog.P)) {
                    Db.actualizar(c, "INSERT INTO perfil_permiso (id_perfil, id_permiso) VALUES (?,?) ON CONFLICT DO NOTHING", idProp, idPerm);
                }
                if (s.perfiles().contains(CommandCatalog.C)) {
                    Db.actualizar(c, "INSERT INTO perfil_permiso (id_perfil, id_permiso) VALUES (?,?) ON CONFLICT DO NOTHING", idCli, idPerm);
                }
            }

            String correo = AppConfig.get("owner.correo");
            if (!correo.isEmpty() && Db.uno(c, "SELECT 1 FROM usuario WHERE lower(correo) = lower(?)", correo).isEmpty()) {
                String ci = AppConfig.get("owner.ci", "ADMIN-001");
                Db.actualizar(c, "INSERT INTO persona (ci, nombres, apellidos) VALUES (?,?,?) ON CONFLICT (ci) DO NOTHING",
                        ci, AppConfig.get("owner.nombres", "Propietario"), AppConfig.get("owner.apellidos", "UNEXO"));
                Db.actualizar(c, "INSERT INTO usuario (correo, password_hash, ci_persona, id_perfil) VALUES (?,?,?,?)",
                        correo.toLowerCase(), Passwords.hash(AppConfig.get("owner.password", "cambiar123")), ci, idProp);
                Db.actualizar(c, "INSERT INTO propietario (ci_persona, cargo, fecha_inicio) VALUES (?, 'Propietario', CURRENT_DATE) "
                        + "ON CONFLICT (ci_persona) DO NOTHING", ci);
                System.out.println("Propietario semilla creado: " + correo);
            }
            return null;
        });
        System.out.println("Permisos sincronizados: " + CommandCatalog.todos().size() + " comandos.");
    }
}
