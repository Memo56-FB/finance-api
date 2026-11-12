# Design

## Context

La motivación está en [proposal.md](proposal.md); el contrato verificable está en [user-registration](specs/user-registration/spec.md).

La inspección del proyecto confirmó:

- Java 25, Spring Boot 4.1.2, MVC, JDBC, Flyway y PostgreSQL; `pom.xml` todavía no incluye Bean Validation.
- `AuthController` expone CSRF y `/me`. `SecurityConfiguration` implementa el login de formulario, sesiones, CORS, CSRF y un `PasswordEncoder` delegado que genera BCrypt.
- `JdbcUserDetailsManager` usa email como `users.username` y consulta con `lower(?)`. `V1__create_users.sql` define la clave primaria de email normalizado y las autoridades, pero no nombre.
- `LocalUserConfiguration` ya usa `TransactionTemplate` y exige de 12 caracteres a 72 bytes UTF-8 al usuario inicial. El mínimo de 8 solicitado corresponde al nuevo registro; esta diferencia se documentará explícitamente.
- `AuthFlowTest` usa MockMvc, Jackson 3 y PostgreSQL 18 en Testcontainers con `@ServiceConnection`. Las clases `*Test` se ejecutan con Surefire; no es necesario incorporar Failsafe ni cambiar sus nombres a `*IT`.

## Goals / Non-Goals

**Goals:**

- Implementar el caso de uso dentro de `dev.memofb.finance.auth` con las herramientas ya utilizadas para persistencia, transacciones y seguridad.
- Concentrar normalización y validación en la entrada; mantener atómicos los cambios de cuenta y conservar la compatibilidad del esquema JDBC de autenticación.
- Comprobar las reglas de entrada rápidamente y reservar PostgreSQL para persistencia, concurrencia, rollback y el flujo real de seguridad.

**Non-Goals:**

- No introducir una entidad JPA, un repositorio genérico, otro proveedor de identidad ni otro algoritmo de contraseñas.
- No agregar infraestructura de correo, caché, sesiones compartidas o cobertura instrumental. El alcance funcional excluido se detalla en la propuesta.

## Decisions

### 1. Contrato JSON independiente del login

Agregar `POST /api/auth/register` a `AuthController`, con un `RegistrationRequest` y una respuesta pequeña `RegisteredUser(name, email)`. Aceptar `application/json` y devolver `201 application/json`. No inventar una URL de consulta de usuarios ni una cabecera `Location` hacia `/me`, porque el registro no autentica.

Permitir exclusivamente `POST /api/auth/register` en la cadena existente. El cliente obtiene CSRF con el GET actual y conserva la cookie de sesión; después del registro puede enviar el formulario al login actual. Mantener el contrato de `/me` y las reglas CORS presentes.

Se elige JSON para un DTO validado explícito. Reutilizar el filtro de login como registro mezclaría creación de cuenta con autenticación; el login automático añadiría cambios al contexto de seguridad y al ciclo CSRF no requeridos.

### 2. Validación declarativa y una comprobación del tamaño UTF-8

Agregar `spring-boot-starter-validation` sin versión explícita y usar `@Valid @RequestBody`. Normalizar `name` y `email` en el constructor compacto del record, tolerando `null` para que Bean Validation lo reporte. `name.strip()` conserva espacios interiores; `email.strip().toLowerCase(Locale.ROOT)` mantiene la convención de los usuarios actuales. No transformar `password`.

| Campo | Regla e implementación prevista |
| --- | --- |
| `name` | `@NotBlank`, `@Size(max = 100)` después de normalizar. El mínimo efectivo es 1. |
| `email` | `@NotBlank`, `@Email`, `@Size(max = 254)` después de normalizar. Sin regex propia ni consulta a proveedores de correo. |
| `password` | `@NotBlank`, `@Size(min = 8)` y comprobación de máximo 72 bytes mediante `StandardCharsets.UTF_8`, antes del encoder. |

Las longitudes de texto siguen la semántica de Bean Validation sobre `String` (unidades UTF-16), coherente con el uso actual de `String.length()`; el límite criptográfico se mide por separado en bytes UTF-8. Probar también entradas multibyte para no confundir ambos límites. No exigir mayúsculas, números ni símbolos.

Para el límite en bytes, usar una comprobación pequeña en el DTO con Bean Validation, por ejemplo un método `@AssertTrue` que trate `null` como válido para dejarlo a `@NotBlank`, excluido de JSON. Su error se presenta bajo `password`, sin exponer el nombre del método auxiliar. No crear un framework de validadores para una sola regla.

Se elige 100 como límite del nombre porque no existe uno previo; se registra como decisión de este cambio. El máximo de email y el límite de bytes provienen del esquema y del encoder actuales. Usar solo `@Size(max = 72)` para la contraseña no verifica el límite UTF-8.

### 3. Migración compatible con usuarios sin nombre

Crear `V2__add_user_name.sql` que añada `users.name VARCHAR(100)` nullable. La obligatoriedad se impone en el nuevo endpoint; cuentas anteriores, bootstrap local y fixtures de autenticación pueden conservar `NULL`.

No actualizar ni rellenar nombres a partir del email. Mantener las consultas de autenticación existentes, la clave primaria normalizada y las autoridades. Una columna `NOT NULL` obligaría a inventar datos de perfil o modificar el bootstrap y todos los escritores actuales sin un requisito que lo justifique. Una tabla de perfil separada agregaría una relación para un único campo.

### 4. Reutilizar la creación JDBC con una transacción de servicio

Crear `RegistrationService` con inyección por constructor de `JdbcUserDetailsManager`, `PasswordEncoder`, `JdbcTemplate` y `TransactionTemplate`. El servicio codifica la contraseña validada antes de abrir la transacción, evitando mantener una conexión durante BCrypt.

Dentro de `TransactionTemplate`, crear el usuario habilitado mediante `users.createUser`, con la contraseña codificada y `roles("USER")`, y guardar el nombre mediante un único `UPDATE users SET name = ? WHERE username = ?` parametrizado. Ambos componentes JDBC comparten el mismo `DataSource`, por lo que la creación de usuario, sus autoridades y el nombre se confirman o revierten juntos. Devolver la respuesta solo después del commit.

La clave primaria de `users.username` es la autoridad para detectar duplicados. No depender de una consulta previa `userExists`, que no protege frente a concurrencia. Convertir únicamente el conflicto de clave duplicada al crear el usuario en una excepción de email registrado y dejar que abandone la transacción para provocar rollback. Otras fallas de integridad o acceso a datos deben propagarse como errores internos, sin ser clasificadas como `409`.

Esta opción reutiliza el almacenamiento de Spring Security y el patrón transaccional local. SQL manual para insertar tanto usuarios como autoridades duplicaría el trabajo de `JdbcUserDetailsManager`; agregar otro repositorio no aporta una responsabilidad adicional para el único `UPDATE`.

### 5. Errores pequeños, sin credenciales

Agregar un único advice de `auth`, basado en `ResponseEntityExceptionHandler`, para validación del DTO, cuerpo ilegible y la excepción de email registrado. Mantener los `401` y `403` de los filtros tal como están.

Para validación, devolver `ProblemDetail` con `status: 400`, mensajes fijos y `errors: [{ "field": "password", "message": "..." }]`; el orden de los errores no forma parte del contrato. Construirlos a partir del campo y un mensaje controlado, nunca de `rejectedValue`, `BindingResult.toString()` ni del texto completo de la excepción. Para cuerpo ilegible, usar un detalle genérico. Para email duplicado, devolver `409` sin detalles de la cuenta ni del SQL.

No registrar cuerpos de registro ni objetos de credenciales. El record de entrada debe omitir o enmascarar `password` en `toString()` para evitar su exposición accidental; nunca serializarlo como respuesta. No registrar excepciones de validación completas, que pueden incorporar valores rechazados.

Se usa la representación estándar de Spring en lugar de crear una jerarquía de envelopes de error. El advice cubre solo las necesidades de este endpoint y no rediseña los errores de autenticación.

### 6. Pruebas por responsabilidad

| Prueba prevista | Evidencia que debe aportar |
| --- | --- |
| `RegistrationRequestTest` con el Validator real | Campos omitidos/nulos/blancos; nombre normalizado, límites 1/100/101; email inválido y límites válidos 254/255; contraseña 7/8 caracteres, 72/73 bytes ASCII y 36/37 `é`; conservación de contraseña y redacción de `toString()`. Usar casos parametrizados. |
| `RegistrationControllerTest` con `@WebMvcTest` | JSON válido → `201`; valores inválidos y cuerpos vacíos/malformados → `400`; duplicado → `409`; formato y privacidad de problemas. Importar la seguridad real y mockear servicio y dependencias JDBC del slice con `@MockitoBean`; conservar filtros. Verificar que un cuerpo inválido no invoque el servicio. |
| Ampliación de `AuthFlowTest` | Registro real con token de `/csrf` y sesión anónima, persistencia de nombre/email/estado/rol/hash, `/me` → `401` antes del login, login posterior → `204`, `/me` → `200`, contraseña sin recorte, CSRF ausente/inválido → `403`, preflight CORS permitido y rechazado. |
| Casos PostgreSQL en `AuthFlowTest` | Duplicados normalizados y cuenta deshabilitada sin sobrescritura; dos solicitudes concurrentes con resultados `201`/`409` y una sola cuenta completa; fallo al persistir el nombre revierte usuario y autoridad. |
| `UserNameMigrationTest` con PostgreSQL | Aplicar solo V1 en un esquema aislado del contenedor, insertar una cuenta y autoridad sintéticas, aplicar V2 y verificar nombre nulo y datos originales intactos. Las pruebas de arranque verifican también V1 + V2 sobre base vacía. |
| Regresiones existentes | `LocalUserConfigurationTest` y los tests actuales de login, sesión, logout, CORS y cuentas deshabilitadas siguen pasando. |

Para probar rollback, provocar un fallo real en el último `UPDATE` con una restricción temporal de prueba sobre un nombre reservado, invocar el bean del servicio y verificar cero filas nuevas en `users` y `authorities`; retirar la restricción en `finally`. Esto prueba la transacción JDBC sin simular el gestor transaccional. No envolver el test en una transacción exterior que oculte el commit/rollback del servicio.

La prueba concurrente debe usar dos sesiones/tokens válidos separados, coordinar el inicio de las solicitudes y limitar la espera; no usar pausas arbitrarias. Consultar la base para verificar tanto unicidad como consistencia de los datos de la solicitud ganadora.

Mantener fixtures aislados y limpiar únicamente cuentas/esquemas del contenedor de pruebas. Los emails de tamaño límite deben tener segmentos válidos para que la prueba mida longitud total y no un formato roto. Reutilizar MockMvc y Jackson 3 en vez de migrar los tests existentes a otra API.

## Risks / Trade-offs

- [Nombre nullable fuera del registro] → Permite conservar cuentas anteriores y el bootstrap. El endpoint exige nombre; un futuro perfil deberá admitir cuentas sin nombre.
- [Conflicto `409` permite inferir que un email existe] → La respuesta no incorpora más datos de la cuenta. La protección contra abuso debe contemplar el nuevo endpoint cuando se configure el límite de intentos del proxy ya pendiente en el proyecto.
- [Diferencia 8 en registro / 12 en bootstrap] → Aclarar ambas reglas en el README; no reducir accidentalmente el mínimo del usuario inicial local.
- [BCrypt tiene un límite en bytes] → Validarlo antes de codificar y probar caracteres multibyte; no truncar la contraseña.
- [Integración y migración requieren Docker] → Ejecutar `./mvnw verify` con Docker disponible. Una ejecución parcial sin PostgreSQL no valida atomicidad ni concurrencia.

## Migration Plan

1. Incorporar el starter y el código junto con V2, dejando V1 intacta. Flyway aplica V2 antes de servir solicitudes.
2. Verificar en PostgreSQL tanto una base nueva como una base en V1 con una cuenta existente; ejecutar la suite completa antes de desplegar.
3. Si se revierte la aplicación, conservar la columna nullable: el código anterior la ignora y las cuentas creadas siguen siendo utilizables mediante el login previo. No borrar la columna ni los nombres como parte del rollback de aplicación.

Referencias técnicas: validación de DTOs con `@Valid` según [Spring MVC](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html), protección del POST según [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) y reutilización de `PasswordEncoder` según [Spring Security Password Storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html). Las decisiones de alcance y los límites concretos provienen de la solicitud y del código inspeccionado.
