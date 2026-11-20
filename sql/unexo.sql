-- =========================================================
-- UNEXO - Sistema Email-Driven (Grupo 06sa) - PostgreSQL
-- Base de datos: db_grupo06sa  (servidor db.tecnoweb.org.bo)
-- =========================================================

-- ---------- USUARIOS Y PERFILES ----------
CREATE TABLE persona (
    ci               VARCHAR(20)  PRIMARY KEY,
    nombres          VARCHAR(100) NOT NULL,
    apellidos        VARCHAR(100) NOT NULL,
    telefono         VARCHAR(20),
    direccion        VARCHAR(200),
    genero           CHAR(1) CHECK (genero IN ('M','F')),
    fecha_nacimiento DATE,
    fecha_registro   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE perfil (
    id          SERIAL PRIMARY KEY,
    nombre      VARCHAR(50) NOT NULL UNIQUE,
    descripcion TEXT,
    estado      BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE permiso (
    id          SERIAL PRIMARY KEY,
    comando     VARCHAR(10)  NOT NULL UNIQUE,
    descripcion VARCHAR(150)
);

CREATE TABLE perfil_permiso (
    id_perfil        INT NOT NULL REFERENCES perfil(id),
    id_permiso       INT NOT NULL REFERENCES permiso(id),
    fecha_asignacion TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id_perfil, id_permiso)
);

CREATE TABLE usuario (
    id             SERIAL PRIMARY KEY,
    correo         VARCHAR(150) NOT NULL UNIQUE,
    password_hash  VARCHAR(100) NOT NULL,
    foto_url       VARCHAR(255),
    estado         BOOLEAN NOT NULL DEFAULT TRUE,
    fecha_creacion TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ci_persona     VARCHAR(20) NOT NULL UNIQUE REFERENCES persona(ci),
    id_perfil      INT NOT NULL REFERENCES perfil(id)
);

CREATE TABLE propietario (
    id           SERIAL PRIMARY KEY,
    cargo        VARCHAR(80),
    fecha_inicio DATE,
    ci_persona   VARCHAR(20) NOT NULL UNIQUE REFERENCES persona(ci)
);

CREATE TABLE cliente (
    id             SERIAL PRIMARY KEY,
    tipo_cliente   VARCHAR(20) NOT NULL DEFAULT 'EXTERNO'
                   CHECK (tipo_cliente IN ('ESTUDIANTE','PROFESIONAL','EXTERNO')),
    institucion    VARCHAR(120),
    profesion      VARCHAR(80),
    fecha_registro TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ci_persona     VARCHAR(20) NOT NULL UNIQUE REFERENCES persona(ci)
);

CREATE TABLE instructor (
    id           SERIAL PRIMARY KEY,
    especialidad VARCHAR(100),
    estado       BOOLEAN NOT NULL DEFAULT TRUE,
    ci_persona   VARCHAR(20) NOT NULL UNIQUE REFERENCES persona(ci)
);

-- ---------- CATÁLOGO Y OFERTA ----------
CREATE TABLE categoria (
    id          SERIAL PRIMARY KEY,
    nombre      VARCHAR(80) NOT NULL UNIQUE,
    descripcion TEXT,
    estado      BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE curso (
    id            SERIAL PRIMARY KEY,
    codigo        VARCHAR(20)  NOT NULL UNIQUE,
    nombre        VARCHAR(150) NOT NULL,
    descripcion   TEXT,
    carga_horaria INT NOT NULL CHECK (carga_horaria > 0),
    requisitos    TEXT,
    estado        BOOLEAN NOT NULL DEFAULT TRUE,
    id_categoria  INT NOT NULL REFERENCES categoria(id)
);

CREATE TABLE oferta_comercial (
    id                SERIAL PRIMARY KEY,
    precio            NUMERIC(10,2) NOT NULL CHECK (precio > 0),
    moneda            CHAR(3) NOT NULL DEFAULT 'BOB',
    descuento_contado NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (descuento_contado BETWEEN 0 AND 100),
    max_cuotas        INT NOT NULL DEFAULT 1 CHECK (max_cuotas BETWEEN 1 AND 12),
    fecha_inicio      DATE NOT NULL,
    fecha_fin         DATE NOT NULL,
    estado            BOOLEAN NOT NULL DEFAULT TRUE,
    id_curso          INT NOT NULL REFERENCES curso(id),
    CHECK (fecha_fin >= fecha_inicio)
);

-- ---------- COHORTES, GRUPOS Y LOGÍSTICA ----------
CREATE TABLE cohorte (
    id           SERIAL PRIMARY KEY,
    codigo       VARCHAR(20) NOT NULL UNIQUE,
    gestion      VARCHAR(10) NOT NULL,
    fecha_inicio DATE NOT NULL,
    fecha_fin    DATE NOT NULL,
    cupo_minimo  INT NOT NULL DEFAULT 1 CHECK (cupo_minimo > 0),
    estado       VARCHAR(15) NOT NULL DEFAULT 'PLANIFICADA'
                 CHECK (estado IN ('PLANIFICADA','EN_CURSO','CERRADA','CANCELADA')),
    id_curso     INT NOT NULL REFERENCES curso(id),
    CHECK (fecha_fin >= fecha_inicio)
);

CREATE TABLE grupo (
    id             SERIAL PRIMARY KEY,
    codigo         VARCHAR(20) NOT NULL UNIQUE,
    modalidad      VARCHAR(12) NOT NULL CHECK (modalidad IN ('VIRTUAL','PRESENCIAL','HIBRIDA')),
    cupo_maximo    INT NOT NULL CHECK (cupo_maximo > 0),
    inscritos      INT NOT NULL DEFAULT 0,
    fecha_apertura DATE,
    estado         VARCHAR(15) NOT NULL DEFAULT 'PLANIFICADO'
                   CHECK (estado IN ('PLANIFICADO','ABIERTO','CERRADO','CANCELADO')),
    id_cohorte     INT NOT NULL REFERENCES cohorte(id),
    id_instructor  INT REFERENCES instructor(id),
    CHECK (inscritos BETWEEN 0 AND cupo_maximo)
);

CREATE TABLE aula (
    id        SERIAL PRIMARY KEY,
    nombre    VARCHAR(50) NOT NULL UNIQUE,
    ubicacion VARCHAR(150),
    capacidad INT NOT NULL CHECK (capacidad > 0),
    estado    BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE horario_clase (
    id          SERIAL PRIMARY KEY,
    dia_semana  VARCHAR(10) NOT NULL
                CHECK (dia_semana IN ('LUNES','MARTES','MIERCOLES','JUEVES','VIERNES','SABADO','DOMINGO')),
    hora_inicio TIME NOT NULL,
    hora_fin    TIME NOT NULL,
    modalidad   VARCHAR(12) NOT NULL CHECK (modalidad IN ('VIRTUAL','PRESENCIAL')),
    plataforma  VARCHAR(50),
    enlace      VARCHAR(255),
    estado      BOOLEAN NOT NULL DEFAULT TRUE,
    id_grupo    INT NOT NULL REFERENCES grupo(id),
    id_aula     INT REFERENCES aula(id),
    CHECK (hora_fin > hora_inicio),
    CHECK ((modalidad = 'PRESENCIAL' AND id_aula IS NOT NULL)
        OR (modalidad = 'VIRTUAL'    AND enlace  IS NOT NULL))
);

-- ---------- MATRÍCULAS ----------
CREATE TABLE matricula (
    id          SERIAL PRIMARY KEY,
    codigo      VARCHAR(20) NOT NULL UNIQUE,
    fecha       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    monto_total NUMERIC(10,2) NOT NULL CHECK (monto_total >= 0),
    descuento   NUMERIC(10,2) NOT NULL DEFAULT 0,
    tipo_pago   VARCHAR(10) NOT NULL CHECK (tipo_pago IN ('CONTADO','CUOTAS')),
    nro_cuotas  INT NOT NULL DEFAULT 1 CHECK (nro_cuotas >= 1),
    estado      VARCHAR(15) NOT NULL DEFAULT 'PREINSCRITO'
                CHECK (estado IN ('EN_ESPERA','PREINSCRITO','MATRICULADO','RETIRADO','FINALIZADO','ANULADO')),
    id_cliente  INT NOT NULL REFERENCES cliente(id),
    id_grupo    INT NOT NULL REFERENCES grupo(id),
    id_oferta   INT NOT NULL REFERENCES oferta_comercial(id),
    UNIQUE (id_cliente, id_grupo)
);

CREATE TABLE cuota (
    id                SERIAL PRIMARY KEY,
    numero            INT NOT NULL CHECK (numero >= 1),
    monto             NUMERIC(10,2) NOT NULL CHECK (monto > 0),
    fecha_vencimiento DATE NOT NULL,
    fecha_pago        DATE,
    estado            VARCHAR(10) NOT NULL DEFAULT 'PENDIENTE'
                      CHECK (estado IN ('PENDIENTE','PAGADA','VENCIDA')),
    id_matricula      INT NOT NULL REFERENCES matricula(id),
    UNIQUE (id_matricula, numero)
);

CREATE TABLE historial_estado_matricula (
    id              SERIAL PRIMARY KEY,
    estado_anterior VARCHAR(15),
    estado_nuevo    VARCHAR(15) NOT NULL,
    fecha_cambio    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    observacion     TEXT,
    correo_usuario  VARCHAR(150) NOT NULL,
    id_matricula    INT NOT NULL REFERENCES matricula(id)
);

CREATE TABLE sincronizacion_sis (
    id           SERIAL PRIMARY KEY,
    operacion    VARCHAR(15) NOT NULL CHECK (operacion IN ('ALTA','ACTUALIZACION','BAJA')),
    fecha_envio  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    codigo_http  INT,
    respuesta    TEXT,
    intentos     INT NOT NULL DEFAULT 1,
    estado       VARCHAR(10) NOT NULL DEFAULT 'PENDIENTE'
                 CHECK (estado IN ('PENDIENTE','ENVIADO','ERROR')),
    id_matricula INT NOT NULL REFERENCES matricula(id)
);

-- ---------- AUDITORÍA ----------
CREATE TABLE bitacora_comando (
    id        SERIAL PRIMARY KEY,
    remitente VARCHAR(150) NOT NULL,
    asunto    TEXT,
    comando   VARCHAR(10),
    resultado VARCHAR(10) NOT NULL CHECK (resultado IN ('EXITO','ERROR')),
    detalle   TEXT,
    fecha     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- ÍNDICES ----------
CREATE INDEX idx_matricula_estado ON matricula(estado);
CREATE INDEX idx_cuota_estado     ON cuota(estado, fecha_vencimiento);
CREATE INDEX idx_horario_grupo    ON horario_clase(id_grupo, dia_semana);
CREATE INDEX idx_bitacora_fecha   ON bitacora_comando(fecha);

-- ---------- DATOS INICIALES ----------
INSERT INTO perfil (nombre, descripcion) VALUES
  ('PROPIETARIO', 'Acceso total a los comandos del sistema'),
  ('CLIENTE',     'Consulta de oferta, matrícula propia y estado')
ON CONFLICT (nombre) DO NOTHING;
