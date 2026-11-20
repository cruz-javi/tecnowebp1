package unexo.util;

import unexo.config.AppConfig;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Registro en logs/access.log y logs/error.log. */
public final class AppLogger {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path DIR = Path.of(AppConfig.get("log.dir", "logs"));

    private AppLogger() {
    }

    public static synchronized void access(String remitente, String asunto, String comando, boolean exito, long ms) {
        String linea = String.format("%s | %s | %s | %s | %s | %d ms",
                now(), remitente, limpiar(asunto), comando, exito ? "EXITO" : "ERROR", ms);
        escribir("access.log", linea);
        System.out.println(linea);
    }

    public static synchronized void info(String mensaje) {
        String linea = now() + " | INFO | " + mensaje;
        escribir("access.log", linea);
        System.out.println(linea);
    }

    public static synchronized void error(String contexto, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) {
            t.printStackTrace(new PrintWriter(sw));
        }
        String linea = now() + " | ERROR | " + contexto + (t != null ? " | " + t + System.lineSeparator() + sw : "");
        escribir("error.log", linea);
        System.err.println(now() + " | ERROR | " + contexto + (t != null ? " | " + t.getMessage() : ""));
    }

    public static synchronized void error(String contexto) {
        error(contexto, null);
    }

    private static void escribir(String archivo, String linea) {
        try {
            Files.createDirectories(DIR);
            Files.writeString(DIR.resolve(archivo), linea + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("No se pudo escribir el log " + archivo + ": " + e.getMessage());
        }
    }

    private static String now() {
        return LocalDateTime.now().format(FMT);
    }

    private static String limpiar(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ");
    }
}
