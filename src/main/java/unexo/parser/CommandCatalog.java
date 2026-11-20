package unexo.parser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Catálogo de los 60 comandos del sistema (ver Anexo 12.1 del documento). */
public final class CommandCatalog {

    public static final String P = "PROPIETARIO";
    public static final String C = "CLIENTE";
    public static final String PUB = "PUBLICO";

    private static final Map<String, CommandSpec> SPECS = new LinkedHashMap<>();

    static {
        // ---------- Generales ----------
        def("AUTH", "GEN", "Abrir sesión administrativa (30 min)", Set.of(P, C), "password:pass");
        def("HELP", "GEN", "Listar los comandos permitidos", Set.of(P, C, PUB), "*:star|text");

        // ---------- CU1 Usuarios ----------
        def("REGCLI", "CU1", "Autorregistro de cliente (correo = remitente)", Set.of(PUB),
                "ci:ci", "nombres:text", "apellidos:text", "telefono:text", "genero:enum:M,F", "fecha_nac:date", "password:pass");
        def("INSUSU", "CU1", "Registrar usuario", Set.of(P),
                "ci:ci", "nombres:text", "apellidos:text", "telefono:text", "direccion:text", "genero:enum:M,F",
                "fecha_nac:date", "correo:email", "password:pass", "perfil:enum:PROPIETARIO,CLIENTE");
        def("UPDUSU", "CU1", "Modificar usuario", Set.of(P, C),
                "ci:ci", "nombres:text", "apellidos:text", "telefono:text", "direccion:text", "genero:enum:M,F",
                "fecha_nac:date", "correo:email");
        def("DELUSU", "CU1", "Baja lógica de usuario", Set.of(P), "ci:ci");
        def("LISUSU", "CU1", "Listar usuarios", Set.of(P), "filtro:enum:*,PROPIETARIO,CLIENTE");
        def("GETUSU", "CU1", "Ver un usuario", Set.of(P, C), "ci:ci");
        defAdj("FOTUSU", "CU1", "Actualizar foto (JPG/PNG adjunta)", Set.of(P, C), "ci:ci");
        def("INSCLI", "CU1", "Datos complementarios de cliente", Set.of(P, C),
                "ci:ci", "tipo_cliente:enum:ESTUDIANTE,PROFESIONAL,EXTERNO", "institucion:opt:text", "profesion:opt:text");

        // ---------- CU2 Perfiles ----------
        def("INSPRF", "CU2", "Registrar perfil", Set.of(P), "nombre:text", "descripcion:text");
        def("UPDPRF", "CU2", "Modificar perfil", Set.of(P), "id:int", "nombre:text", "descripcion:text");
        def("DELPRF", "CU2", "Desactivar perfil", Set.of(P), "id:int");
        def("LISPRF", "CU2", "Listar perfiles", Set.of(P), "*:star|text");
        def("ASGPRM", "CU2", "Asignar permiso a perfil", Set.of(P), "id_perfil:int", "comando:text");
        def("QUIPRM", "CU2", "Quitar permiso a perfil", Set.of(P), "id_perfil:int", "comando:text");
        def("LISPRM", "CU2", "Listar permisos de un perfil", Set.of(P), "id_perfil:int");
        def("ASGPRF", "CU2", "Asignar perfil a usuario", Set.of(P), "ci:ci", "id_perfil:int");

        // ---------- CU3 Catálogo y oferta ----------
        def("INSCAT", "CU3", "Registrar categoría", Set.of(P), "nombre:text", "descripcion:text");
        def("LISCAT", "CU3", "Listar categorías", Set.of(P, C), "*:star|text");
        def("INSCUR", "CU3", "Registrar curso", Set.of(P),
                "codigo:ci", "nombre:text", "descripcion:text", "carga_horaria:int", "requisitos:text", "id_categoria:int");
        def("UPDCUR", "CU3", "Modificar curso", Set.of(P),
                "codigo:ci", "nombre:text", "descripcion:text", "carga_horaria:int", "requisitos:text", "id_categoria:int");
        def("DELCUR", "CU3", "Desactivar curso", Set.of(P), "codigo:ci");
        def("LISCUR", "CU3", "Listar cursos", Set.of(P, C), "id_categoria:star|int");
        def("GETCUR", "CU3", "Ver curso con su oferta vigente", Set.of(P, C), "codigo:ci");
        def("INSOFE", "CU3", "Registrar oferta comercial", Set.of(P),
                "codigo_curso:ci", "precio:dec", "moneda:enum:BOB,USD", "descuento_contado:dec", "max_cuotas:int",
                "fecha_inicio:date", "fecha_fin:date");
        def("UPDOFE", "CU3", "Modificar oferta", Set.of(P),
                "id:int", "precio:dec", "descuento_contado:dec", "max_cuotas:int", "fecha_fin:date");
        def("LISOFE", "CU3", "Listar ofertas vigentes", Set.of(P, C), "*:star|text");

        // ---------- CU4 Cohortes y grupos ----------
        def("INSCOH", "CU4", "Planificar cohorte", Set.of(P),
                "codigo:ci", "codigo_curso:ci", "gestion:text", "fecha_inicio:date", "fecha_fin:date", "cupo_minimo:int");
        def("UPDCOH", "CU4", "Modificar cohorte", Set.of(P),
                "codigo:ci", "fecha_inicio:date", "fecha_fin:date", "cupo_minimo:int",
                "estado:enum:PLANIFICADA,EN_CURSO,CERRADA,CANCELADA");
        def("LISCOH", "CU4", "Listar cohortes", Set.of(P), "codigo_curso:star|ci");
        def("INSINS", "CU4", "Registrar instructor (persona existente)", Set.of(P), "ci:ci", "especialidad:text");
        def("LISINS", "CU4", "Listar instructores", Set.of(P), "*:star|text");
        def("INSGRU", "CU4", "Crear grupo", Set.of(P),
                "codigo:ci", "codigo_cohorte:ci", "id_instructor:int", "modalidad:enum:VIRTUAL,PRESENCIAL,HIBRIDA", "cupo_maximo:int");
        def("ABRGRU", "CU4", "Abrir grupo", Set.of(P), "codigo:ci");
        def("CERGRU", "CU4", "Cerrar grupo", Set.of(P), "codigo:ci");
        def("CANGRU", "CU4", "Cancelar grupo", Set.of(P), "codigo:ci", "motivo:text");
        def("LISGRU", "CU4", "Listar grupos", Set.of(P, C), "codigo_cohorte:star|ci");

        // ---------- CU5 Logística de clases ----------
        def("INSAUL", "CU5", "Registrar aula", Set.of(P), "nombre:text", "ubicacion:text", "capacidad:int");
        def("LISAUL", "CU5", "Listar aulas", Set.of(P), "*:star|text");
        def("INSHOR", "CU5", "Registrar horario", Set.of(P),
                "codigo_grupo:ci", "dia:enum:LUNES,MARTES,MIERCOLES,JUEVES,VIERNES,SABADO,DOMINGO", "hora_inicio:time",
                "hora_fin:time", "modalidad:enum:VIRTUAL,PRESENCIAL", "id_aula:opt:int", "plataforma:opt:text", "enlace:opt:text");
        def("UPDHOR", "CU5", "Modificar horario", Set.of(P),
                "id:int", "dia:enum:LUNES,MARTES,MIERCOLES,JUEVES,VIERNES,SABADO,DOMINGO", "hora_inicio:time",
                "hora_fin:time", "modalidad:enum:VIRTUAL,PRESENCIAL", "id_aula:opt:int", "plataforma:opt:text", "enlace:opt:text");
        def("DELHOR", "CU5", "Eliminar (desactivar) horario", Set.of(P), "id:int");
        def("LISHOR", "CU5", "Listar horarios de un grupo", Set.of(P, C), "codigo_grupo:ci");

        // ---------- CU6 Matrículas ----------
        def("INSMAT", "CU6", "Matricular", Set.of(P, C),
                "ci_cliente:ci", "codigo_grupo:ci", "tipo_pago:enum:CONTADO,CUOTAS", "nro_cuotas:int");
        def("GETMAT", "CU6", "Ver matrícula y cuotas", Set.of(P, C), "codigo_matricula:ci");
        def("LISMAT", "CU6", "Listar matrículas (*, CI o código de grupo)", Set.of(P, C), "filtro:star|ci");
        def("CAMGRU", "CU6", "Cambiar de grupo (misma cohorte)", Set.of(P), "codigo_matricula:ci", "codigo_grupo_nuevo:ci");
        def("PAGCUO", "CU6", "Registrar pago de cuota", Set.of(P), "codigo_matricula:ci", "nro_cuota:int");
        def("LISCUO", "CU6", "Listar cuotas", Set.of(P, C), "codigo_matricula:ci");

        // ---------- CU7 Estado de matrícula y SIS ----------
        def("ESTMAT", "CU7", "Consultar estado actual", Set.of(P, C), "codigo_matricula:ci");
        def("UPDEST", "CU7", "Cambiar estado", Set.of(P),
                "codigo_matricula:ci", "nuevo_estado:enum:EN_ESPERA,PREINSCRITO,MATRICULADO,RETIRADO,FINALIZADO,ANULADO",
                "observacion:text");
        def("HISEST", "CU7", "Historial de estados", Set.of(P, C), "codigo_matricula:ci");
        def("SINSIS", "CU7", "Forzar sincronización con el SIS (* = pendientes)", Set.of(P), "codigo_matricula:star|ci");
        def("LISSIS", "CU7", "Listar sincronizaciones", Set.of(P), "filtro:enum:*,PENDIENTE,ENVIADO,ERROR");

        // ---------- CU8 Reportes ----------
        def("REPMAT", "CU8", "Matrículas por estado y curso", Set.of(P), "fecha_ini:date", "fecha_fin:date");
        def("REPING", "CU8", "Ingresos por curso", Set.of(P), "fecha_ini:date", "fecha_fin:date");
        def("REPGRU", "CU8", "Ocupación de grupos", Set.of(P), "codigo_cohorte:star|ci");
        def("REPCUR", "CU8", "Cursos y cohortes por gestión", Set.of(P), "gestion:text");
        def("ESTGEN", "CU8", "Estadísticas generales con gráficos", Set.of(P), "*:star|text");
    }

    private CommandCatalog() {
    }

    private static void def(String nombre, String cu, String desc, Set<String> perfiles, String... params) {
        registrar(nombre, cu, desc, perfiles, false, params);
    }

    private static void defAdj(String nombre, String cu, String desc, Set<String> perfiles, String... params) {
        registrar(nombre, cu, desc, perfiles, true, params);
    }

    private static void registrar(String nombre, String cu, String desc, Set<String> perfiles, boolean adj, String... params) {
        List<CommandSpec.Param> ps = new ArrayList<>();
        for (String p : params) {
            int k = p.indexOf(':');
            ps.add(new CommandSpec.Param(p.substring(0, k), p.substring(k + 1)));
        }
        SPECS.put(nombre, new CommandSpec(nombre, cu, desc, List.copyOf(ps), perfiles, adj));
    }

    public static CommandSpec buscar(String nombre) {
        return SPECS.get(nombre);
    }

    public static Collection<CommandSpec> todos() {
        return SPECS.values();
    }
}
