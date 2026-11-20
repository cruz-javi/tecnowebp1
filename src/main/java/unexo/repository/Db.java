package unexo.repository;

import unexo.config.AppConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Acceso a PostgreSQL. TODAS las consultas usan PreparedStatement:
 * los valores enviados por el usuario viajan como parámetros y nunca se concatenan al SQL.
 */
public final class Db {

    private Db() {
    }

    public static Connection conectar() throws SQLException {
        Connection c = DriverManager.getConnection(
                AppConfig.get("db.url", "jdbc:postgresql://db.tecnoweb.org.bo:5432/db_grupo06sa"),
                AppConfig.get("db.user", "grupo06sa"),
                AppConfig.get("db.password"));
        c.setAutoCommit(true);
        return c;
    }

    /** Ejecuta un bloque dentro de una transacción (commit / rollback). */
    public static <T> T transaccion(TxBloque<T> bloque) throws SQLException {
        try (Connection c = conectar()) {
            c.setAutoCommit(false);
            try {
                T r = bloque.ejecutar(c);
                c.commit();
                return r;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    @FunctionalInterface
    public interface TxBloque<T> {
        T ejecutar(Connection c) throws SQLException;
    }

    // ---------- variantes con conexión propia ----------

    public static List<Map<String, Object>> consultar(String sql, Object... params) throws SQLException {
        try (Connection c = conectar()) {
            return consultar(c, sql, params);
        }
    }

    public static Optional<Map<String, Object>> uno(String sql, Object... params) throws SQLException {
        try (Connection c = conectar()) {
            return uno(c, sql, params);
        }
    }

    public static int actualizar(String sql, Object... params) throws SQLException {
        try (Connection c = conectar()) {
            return actualizar(c, sql, params);
        }
    }

    // ---------- variantes dentro de una transacción ----------

    public static List<Map<String, Object>> consultar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = preparar(c, sql, params); ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            List<Map<String, Object>> filas = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> fila = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    fila.put(md.getColumnLabel(i), rs.getObject(i));
                }
                filas.add(fila);
            }
            return filas;
        }
    }

    public static Optional<Map<String, Object>> uno(Connection c, String sql, Object... params) throws SQLException {
        List<Map<String, Object>> filas = consultar(c, sql, params);
        return filas.isEmpty() ? Optional.empty() : Optional.of(filas.get(0));
    }

    public static int actualizar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = preparar(c, sql, params)) {
            return ps.executeUpdate();
        }
    }

    /** INSERT ... RETURNING id */
    public static int insertarId(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = preparar(c, sql, params); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public static int insertarId(String sql, Object... params) throws SQLException {
        try (Connection c = conectar()) {
            return insertarId(c, sql, params);
        }
    }

    private static PreparedStatement preparar(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        return ps;
    }
}
