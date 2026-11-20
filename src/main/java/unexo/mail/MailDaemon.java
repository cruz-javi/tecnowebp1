package unexo.mail;

import unexo.config.AppConfig;
import unexo.repository.Db;
import unexo.router.CommandRouter;
import unexo.util.AppLogger;
import unexo.util.Respuesta;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Demonio de lectura: cada N segundos se conecta por POP3 a la casilla del grupo,
 * procesa el asunto de cada correo, responde por SMTP y elimina el mensaje (DELE).
 * Ante fallos de conexión espera con retroceso exponencial (hasta 2 minutos).
 */
public class MailDaemon {

    private static final long MAX_ESPERA_MS = 120_000;

    private final CommandRouter router = new CommandRouter();
    private final String casilla = AppConfig.get("mail.from", "grupo06sa@tecnoweb.org.bo").toLowerCase(Locale.ROOT);
    private final SmtpClient smtp = new SmtpClient(
            AppConfig.get("smtp.host", "mail.tecnoweb.org.bo"), AppConfig.getInt("smtp.port", 25),
            AppConfig.get("smtp.user"), AppConfig.get("smtp.password"));
    private volatile boolean activo = true;

    public static void main(String[] args) {
        MailDaemon d = new MailDaemon();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> d.activo = false));
        d.ejecutar(args.length > 0 && "--una-vez".equals(args[0]));
    }

    public void ejecutar(boolean unaVez) {
        long intervalo = AppConfig.getInt("poll.interval.ms", 10_000);
        long espera = intervalo;
        int ciclo = 0;
        AppLogger.info("MailDaemon iniciado. Casilla " + casilla + ", intervalo " + intervalo + " ms");
        while (activo) {
            try {
                int n = ciclo();
                if (n > 0) {
                    AppLogger.info("Ciclo terminado: " + n + " correo(s) procesado(s)");
                }
                if (++ciclo % 30 == 0) {
                    router.estados().reintentarPendientes();
                }
                espera = intervalo;
            } catch (Exception e) {
                AppLogger.error("Fallo en el ciclo de lectura POP3", e);
                espera = Math.min(MAX_ESPERA_MS, espera * 2);
                AppLogger.info("Reintentando en " + espera + " ms");
            }
            if (unaVez) {
                return;
            }
            dormir(espera);
        }
    }

    /** Un ciclo completo de lectura. Devuelve la cantidad de correos procesados. */
    int ciclo() throws Exception {
        try (Pop3Client pop = new Pop3Client(AppConfig.get("pop3.host", "mail.tecnoweb.org.bo"),
                AppConfig.getInt("pop3.port", 110), AppConfig.getBool("pop3.ssl", false))) {
            pop.conectar(AppConfig.get("mail.user", "grupo06sa"), AppConfig.get("mail.password"));
            List<Integer> ids = pop.listar();
            int max = AppConfig.getInt("poll.max.mensajes", 20);
            int procesados = 0;
            for (int id : ids) {
                if (procesados >= max) {
                    break;
                }
                String crudo = pop.recuperar(id);
                try {
                    atender(crudo);
                } catch (Exception e) {
                    AppLogger.error("No se pudo atender el mensaje " + id, e);
                }
                pop.eliminar(id); // se elimina siempre para no ejecutar dos veces un comando
                procesados++;
            }
            pop.quit();
            return procesados;
        }
    }

    private void atender(String crudo) {
        long t0 = System.currentTimeMillis();
        Map<String, String> cab = MimeParser.cabeceras(crudo);
        MailMessage msg = MimeParser.parsear(crudo);
        if (debeIgnorarse(msg, cab)) {
            AppLogger.info("Ignorado (rebote/auto-respuesta/propio): " + msg.from() + " | " + msg.subject());
            return;
        }
        CommandRouter.Resultado r = router.procesar(msg);
        Respuesta resp = r.respuesta();
        // Nunca se registra la contraseña de AUTH, REGCLI o INSUSU en los logs ni en la bitácora.
        String asuntoLog = r.comando() != null && java.util.Set.of("AUTH", "REGCLI", "INSUSU").contains(r.comando())
                ? r.comando() + "[...oculto...]" : msg.subject();
        try {
            smtp.enviar(casilla, msg.from(), "RE: " + msg.subject(), resp.getTexto(), resp.getHtml());
        } catch (Exception e) {
            AppLogger.error("SMTP: no se pudo responder a " + msg.from(), e);
        }
        AppLogger.access(msg.from(), asuntoLog, r.comando() == null ? "-" : r.comando(), resp.isExito(),
                System.currentTimeMillis() - t0);
        if (!resp.isExito()) {
            AppLogger.error(msg.from() + " | " + asuntoLog + " | " + resp.getMensaje());
        }
        bitacora(msg.from(), asuntoLog, r);
    }

    private boolean debeIgnorarse(MailMessage msg, Map<String, String> cab) {
        String from = msg.from();
        String auto = cab.getOrDefault("auto-submitted", "no").toLowerCase(Locale.ROOT);
        return from.isEmpty() || from.equals(casilla)
                || from.startsWith("mailer-daemon") || from.startsWith("postmaster") || from.contains("no-reply") || from.contains("noreply")
                || !auto.startsWith("no");
    }

    private static void bitacora(String remitente, String asunto, CommandRouter.Resultado r) {
        try {
            Db.actualizar("INSERT INTO bitacora_comando (remitente, asunto, comando, resultado, detalle) VALUES (?,?,?,?,?)",
                    remitente, asunto, r.comando() == null ? null : r.comando().substring(0, Math.min(10, r.comando().length())),
                    r.respuesta().isExito() ? "EXITO" : "ERROR", r.respuesta().getMensaje());
        } catch (Exception e) {
            AppLogger.error("No se pudo registrar la bitácora", e);
        }
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
