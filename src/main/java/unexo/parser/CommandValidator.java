package unexo.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validación semántica: el comando existe, la cantidad de parámetros es exacta y cada
 * parámetro coincide con el tipo esperado por el esquema de la base de datos.
 * Devuelve los valores normalizados (enumerados en mayúsculas, "-" convertido en null).
 */
public final class CommandValidator {

    private static final Pattern CI = Pattern.compile("[A-Za-z0-9][A-Za-z0-9\\-_.]{1,19}");
    private static final Pattern INT = Pattern.compile("\\d{1,9}");
    private static final Pattern DEC = Pattern.compile("\\d{1,8}([.,]\\d{1,2})?");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern HORA = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    private CommandValidator() {
    }

    public static CommandSpec spec(Comando cmd) throws SintaxisException {
        CommandSpec spec = CommandCatalog.buscar(cmd.nombre());
        if (spec == null) {
            throw new SintaxisException("comando desconocido \"" + cmd.nombre() + "\". Envíe HELP[\"*\"] para ver los comandos disponibles.");
        }
        return spec;
    }

    public static List<String> validar(Comando cmd, CommandSpec spec) throws SintaxisException {
        List<CommandSpec.Param> esperados = spec.params();
        if (cmd.cantidad() != esperados.size()) {
            throw new SintaxisException(spec.nombre() + " requiere " + esperados.size() + " parámetro(s) y recibió "
                    + cmd.cantidad() + ". Formato: " + spec.firma());
        }
        List<String> valores = new ArrayList<>();
        for (int i = 0; i < esperados.size(); i++) {
            CommandSpec.Param p = esperados.get(i);
            valores.add(validarTipo(spec.nombre(), i + 1, p.nombre(), p.tipo(), cmd.p(i)));
        }
        return valores;
    }

    static String validarTipo(String cmd, int n, String nombre, String tipo, String v) throws SintaxisException {
        String ctx = cmd + " parámetro " + n + " (" + nombre + ")";
        if (tipo.startsWith("opt:")) {
            return "-".equals(v) ? null : validarTipo(cmd, n, nombre, tipo.substring(4), v);
        }
        if (tipo.startsWith("star|")) {
            return "*".equals(v) ? "*" : validarTipo(cmd, n, nombre, tipo.substring(5), v);
        }
        if (tipo.startsWith("enum:")) {
            List<String> opciones = Arrays.asList(tipo.substring(5).split(","));
            String up = v.toUpperCase(Locale.ROOT);
            if (!opciones.contains(up)) {
                throw new SintaxisException(ctx + " debe ser uno de " + opciones + ".");
            }
            return up;
        }
        switch (tipo) {
            case "text":
                if (v.length() > 255) {
                    throw new SintaxisException(ctx + " supera los 255 caracteres.");
                }
                return v;
            case "pass":
                if (v.length() < 6) {
                    throw new SintaxisException(ctx + " debe tener al menos 6 caracteres.");
                }
                return v;
            case "ci":
                if (!CI.matcher(v).matches()) {
                    throw new SintaxisException(ctx + " debe ser un código alfanumérico de 2 a 20 caracteres.");
                }
                return v.toUpperCase(Locale.ROOT);
            case "int":
                if (!INT.matcher(v).matches()) {
                    throw new SintaxisException(ctx + " debe ser un número entero positivo.");
                }
                return v;
            case "dec":
                if (!DEC.matcher(v).matches()) {
                    throw new SintaxisException(ctx + " debe ser un número decimal (ej. 450.50).");
                }
                return new BigDecimal(v.replace(',', '.')).toPlainString();
            case "date":
                try {
                    return LocalDate.parse(v).toString();
                } catch (DateTimeParseException e) {
                    throw new SintaxisException(ctx + " debe ser una fecha válida AAAA-MM-DD.");
                }
            case "time":
                if (!HORA.matcher(v).matches()) {
                    throw new SintaxisException(ctx + " debe ser una hora HH:MM (00:00 a 23:59).");
                }
                return LocalTime.parse(v).toString();
            case "email":
                if (!EMAIL.matcher(v).matches()) {
                    throw new SintaxisException(ctx + " no es un correo electrónico válido.");
                }
                return v.toLowerCase(Locale.ROOT);
            default:
                throw new IllegalStateException("Tipo no soportado: " + tipo);
        }
    }
}
