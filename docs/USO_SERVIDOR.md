# UNEXO — Guía de uso en el servidor (Grupo 06)

Pasos para desplegar y operar el sistema **email-driven** de UNEXO contra la
infraestructura de la cátedra (`tecnoweb.org.bo`). El sistema se maneja **solo
por correo**: se escribe un comando en el **asunto** de un correo enviado a la
casilla del grupo y se recibe una respuesta automática `Éxito:` / `Error:`.

> ⚠️ **Credenciales:** nunca se escriben en este archivo ni en el repositorio.
> Van únicamente en `config.properties`, que está en `.gitignore` y **no se sube**.

---

## 1. Infraestructura (cátedra)

| Recurso | Valor |
|---|---|
| Servidor web | `https://www.tecnoweb.org.bo/inf513/grupo06sa` |
| Casilla de correo | `grupo06sa@tecnoweb.org.bo` (POP3 `110` / SMTP `25`, host `mail.tecnoweb.org.bo`) |
| Base de datos | `db.tecnoweb.org.bo:5432`, base `db_grupo06sa`, usuario `grupo06sa` |
| Schema de la app | **`unexo`** (dedicado, para no colisionar con otros proyectos de la base) |

### Nota sobre el schema `unexo`
La base `db_grupo06sa` es compartida y puede contener tablas de otros proyectos
(p. ej. una tabla `persona` con estructura distinta). Por eso UNEXO instala todas
sus tablas en un schema propio llamado `unexo`. La conexión JDBC lo selecciona con
el parámetro `?currentSchema=unexo`:

```
db.url=jdbc:postgresql://db.tecnoweb.org.bo:5432/db_grupo06sa?currentSchema=unexo
```

---

## 2. Requisitos

- **Java 17** (JDK) y **Maven**.

**Windows** (PowerShell):
```powershell
winget install EclipseAdoptium.Temurin.17.JDK Apache.Maven
```
**Linux (Debian/Ubuntu):**
```bash
sudo apt update && sudo apt install -y openjdk-17-jdk maven
```
Verificar:
```bash
java -version
mvn -v
```

---

## 3. Configuración

1. Copiar la plantilla:
   - Windows: `copy config.properties.example config.properties`
   - Linux:   `cp config.properties.example config.properties`
2. Completar en `config.properties`:
   - `mail.user`, `mail.password`, `mail.from` → credenciales de la casilla del grupo.
   - `db.url` (con `?currentSchema=unexo`), `db.user`, `db.password`.
   - **Propietario / administrador semilla** (así se hace admin al ingeniero):
     ```properties
     owner.ci=ING-001
     owner.nombres=Ingeniero
     owner.apellidos=Docente
     owner.correo=correo_del_ingeniero@gmail.com   # <-- correo real del docente
     owner.password=clave_para_el_ingeniero         # <-- la usará en AUTH[...]
     ```

---

## 4. Inicializar la base (una sola vez)

```bash
# Windows
scripts\run.bat init
# Linux
./scripts/run.sh init
```

Crea (si no existen) las 20 tablas en el schema `unexo`, registra los 60 permisos
y da de alta al **propietario semilla** (el correo de `owner.correo`). Debe mostrar
`Permisos sincronizados: 60 comandos.` y `Propietario semilla creado: ...`.

---

## 5. Arrancar el daemon de correo

```bash
# Windows (primer plano; Ctrl+C para detener)
scripts\run.bat
# Linux (segundo plano)
./scripts/run.sh daemon      # detener: ./scripts/run.sh stop
```

El daemon revisa la casilla cada `poll.interval.ms`, procesa cada comando y
responde por SMTP. Bitácora en `logs/access.log` y `logs/error.log`.

---

## 6. Cómo enviar comandos

- Enviar un **correo** a **`grupo06sa@tecnoweb.org.bo`**.
- El **comando va en el ASUNTO** (el cuerpo se ignora). **Un comando por correo.**
- Cada parámetro entre **comillas dobles**. Fechas `AAAA-MM-DD`, horas `HH:MM`,
  `"-"` = vacío. Para una comilla dentro de un texto, escápala con `\"`.
- El sistema responde automáticamente con `Éxito: ...` o `Error: ...`.

### Perfiles
- **PROPIETARIO** (administrador): acceso a los 60 comandos. Debe iniciar sesión
  con `AUTH["su_clave"]` (válida **30 minutos**) antes de operar.
- **CLIENTE**: solo sus propios datos y matrículas.
- **Público** (no registrado): puede autorregistrarse con `REGCLI[...]`.

Para ver los comandos disponibles según tu perfil: `HELP["*"]`.

---

## 7. Hacer administrador al ingeniero

**Opción A (recomendada):** poner su correo en `owner.correo` **antes** de correr
`init` (sección 3 y 4). Queda como PROPIETARIO automáticamente.

**Opción B:** si la base ya está inicializada con otro propietario, desde el correo
de un PROPIETARIO existente enviar (dos correos):
```
AUTH["clave_del_propietario_actual"]
INSUSU["ING-001","Ingeniero","Docente","70000000","Direccion","M","1980-01-01","correo_del_ingeniero@gmail.com","clave","PROPIETARIO"]
```

---

## 8. Secuencia de prueba (CU1 → CU8)

Enviar estos **asuntos** en orden (desde el correo del PROPIETARIO). Empezar por
`AUTH` para abrir la sesión de 30 minutos:

```
AUTH["clave_del_ingeniero"]
HELP["*"]
INSCAT["Tecnología","Cursos de desarrollo web y bases de datos"]
INSCUR["TW-101","Tecnología Web Básica","HTML, CSS y \"JavaScript\"","40","Ninguno","1"]
INSOFE["TW-101","900","BOB","10","3","2026-01-01","2027-12-31"]
GETCUR["TW-101"]
INSUSU["7894561","Ana","Pérez Rojas","71000001","Av. Busch 123","F","2001-05-10","ana@correo.com","ana12345","CLIENTE"]
LISUSU["CLIENTE"]
INSPRF["SECRETARIA","Atención de matrículas"]
LISPRF["*"]
INSINS["7894561","Desarrollo web"]
INSCOH["TW101-2026-2","TW-101","2026-2","2026-12-01","2027-01-31","2"]
INSGRU["G-TW101-A","TW101-2026-2","1","PRESENCIAL","2"]
INSAUL["Aula 2","Módulo 236, piso 2","35"]
INSHOR["G-TW101-A","LUNES","19:00","21:00","PRESENCIAL","1","-","-"]
ABRGRU["G-TW101-A"]
INSMAT["7894561","G-TW101-A","CONTADO","1"]
GETMAT["MAT-0001"]
PAGCUO["MAT-0001","1"]
UPDEST["MAT-0001","MATRICULADO","Pago al contado confirmado"]
HISEST["MAT-0001"]
REPMAT["2026-01-01","2027-12-31"]
REPING["2026-01-01","2027-12-31"]
REPGRU["*"]
ESTGEN["*"]
```

Recorre los 8 casos de uso: catálogo/oferta (CU3), usuarios (CU1), perfiles (CU2),
cohortes/grupos (CU4), logística (CU5), matrícula y pago (CU6), estados (CU7) y
reportes (CU8).

---

## 9. Problemas comunes

| Síntoma | Causa / solución |
|---|---|
| `autenticación requerida` | El PROPIETARIO no envió `AUTH[...]` o pasaron 30 min. Reenviar `AUTH["clave"]`. |
| `el remitente ... no está registrado` | Ese correo no es usuario. Registrarse con `REGCLI[...]` o darlo de alta con `INSUSU`. |
| `su perfil CLIENTE no tiene permiso para ...` | Comando solo de PROPIETARIO. |
| `init` dice "el esquema ya existe" | Normal si las tablas de `unexo` ya estaban creadas; solo sincroniza permisos. |
| No conecta a la BD | Revisar que `db.url` incluya `?currentSchema=unexo` y las credenciales en `config.properties`. |
