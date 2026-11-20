package unexo.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuración leída de config.properties (no versionado).
 * Ruta por defecto: ./config.properties; se puede cambiar con -Dunexo.config=ruta
 */
public final class AppConfig {

    private static final Properties PROPS = new Properties();

    static {
        String ruta = System.getProperty("unexo.config", "config.properties");
        Path path = Path.of(ruta);
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                PROPS.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo leer " + ruta + ": " + e.getMessage(), e);
            }
        } else {
            System.err.println("[AVISO] No existe " + ruta + ". Copie config.properties.example y complete los datos.");
        }
    }

    private AppConfig() {
    }

    public static String get(String key, String def) {
        String env = System.getenv(key.toUpperCase().replace('.', '_'));
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String v = PROPS.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    public static String get(String key) {
        return get(key, "");
    }

    public static int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean getBool(String key, boolean def) {
        return Boolean.parseBoolean(get(key, String.valueOf(def)));
    }
}
