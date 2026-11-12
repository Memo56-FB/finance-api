# Tasks

Contrato de aceptación: [user-registration](specs/user-registration/spec.md). Decisiones y estrategia de pruebas: [design.md](design.md).

## 1. Persistir el nombre sin afectar cuentas actuales

- [ ] 1.1 Crear `src/main/resources/db/migration/V2__add_user_name.sql` con `users.name VARCHAR(100)` nullable, sin modificar V1. Agregar `UserNameMigrationTest` con PostgreSQL que migre V1 con una cuenta y autoridad existentes a V2 y compruebe nombre nulo y conservación de credenciales, estado y autoridades; verificar también que el contexto actual arranque sobre una base vacía.
- [ ] 1.2 Actualizar la sección de almacenamiento del `README.md` para explicar que el nombre es opcional para cuentas anteriores y bootstrap local. Verificar que el texto coincida con V2 y que las pruebas de autenticación de usuarios sin nombre sigan pasando.

## 2. Validar la solicitud de registro

- [x] 2.1 Agregar `spring-boot-starter-validation` en `pom.xml` sin versión explícita. Verificar resolución y compilación con el Maven Wrapper, conservando Java 25, Boot 4.1.2 y los starters de prueba actuales.
- [x] 2.2 Crear `RegistrationRequest` en `auth`: normalizar nombre/email de forma segura ante `null`, aplicar `@NotBlank`, `@Email`, límites de nombre/email, mínimo de contraseña de 8 y máximo de 72 bytes UTF-8; conservar la contraseña exacta y redactar su `toString()`. Agregar `RegistrationRequestTest` parametrizado con Validator real para blancos/nulos, formatos, normalización, límites 1/100/101, 254/255, 7/8 y 72/73 bytes, casos multibyte y redacción; verificar que pase sin Docker.
- [x] 2.3 Documentar en el README las reglas de cada campo y separar claramente los 8 caracteres del registro de los 12 del usuario inicial local. Verificar que los límites documentados correspondan a los casos de `RegistrationRequestTest` y que `LocalUserConfiguration` conserve su política.

## 3. Crear la cuenta de forma atómica

- [ ] 3.1 Implementar `RegistrationService` y la respuesta de nombre/email reutilizando `JdbcUserDetailsManager`, `PasswordEncoder`, `JdbcTemplate` y `TransactionTemplate`: codificar antes de la transacción y crear usuario, `ROLE_USER` y nombre dentro de ella. Agregar un caso PostgreSQL en `AuthFlowTest` que compruebe los campos persistidos, una sola autoridad, hash distinto del texto y `PasswordEncoder.matches` correcto.
- [ ] 3.2 Traducir el conflicto de clave duplicada al crear el usuario en una excepción de email ya registrado, dejando salir la excepción para revertir la transacción; no usar una consulta previa como garantía de unicidad ni convertir cualquier error de base a duplicado. Verificar con PostgreSQL duplicados exactos/normalizados y cuentas deshabilitadas, comparando nombre, hash, estado y autoridades antes y después.
- [ ] 3.3 Agregar una prueba de rollback real que fuerce el fallo del guardado del nombre mediante una restricción temporal del contenedor, la retire en `finally` y compruebe cero filas nuevas en `users` y `authorities`. Verificar que el error no sea clasificado como email duplicado y que el test no use una transacción exterior.

## 4. Exponer y probar el endpoint

- [x] 4.1 Agregar `POST /api/auth/register` a `AuthController` con JSON validado y respuesta `201` de solo nombre/email; permitir ese método y ruta en `SecurityConfiguration` conservando CSRF. Crear `RegistrationControllerTest` con `@WebMvcTest`, filtros activos y servicio mockeado, verificando el éxito anónimo con CSRF, cuerpo normalizado y ausencia de credenciales/redirección.
- [x] 4.2 Agregar el advice de registro con `ProblemDetail` para `400` y `409`, errores de campo seguros y detalle genérico para JSON ilegible. Cubrir en el slice campos omitidos/nulos/blancos/incorrectos, límite UTF-8 reportado en `password`, cuerpo vacío, literal `null`, JSON mal formado y duplicado; verificar `application/problem+json`, ausencia de valores rechazados/hash/contraseña y que los datos inválidos no invoquen el servicio.
- [ ] 4.3 Ampliar `AuthFlowTest` con GET CSRF real → registro de contraseña de exactamente 8 caracteres → `/me` anónimo `401` → login de formulario `204` → `/me` `200`. Verificar persistencia, email normalizado, contrato actual de `/me` y otro caso donde solo la contraseña con sus espacios originales permita autenticar.
- [ ] 4.4 Agregar casos de registro sin CSRF y con CSRF inválido que devuelvan `403` sin persistencia, más preflight del registro desde el origen permitido y otro rechazado. Verificar que sigan pasando los casos existentes de rotación de sesión, logout, CSRF y CORS.
- [ ] 4.5 Probar dos solicitudes concurrentes de registro con el mismo email normalizado, datos diferentes y sesiones/tokens independientes; coordinar el inicio con espera acotada. Verificar resultados `201` y `409`, una sola cuenta con una autoridad y nombre/contraseña correspondientes a la solicitud ganadora, sin depender del orden de los tests.
- [x] 4.6 Completar el README con contrato JSON del registro, ejemplo de solicitud/respuesta, códigos `201`/`400`/`409`/`403` y secuencia CSRF → registro → login usando cookies. Verificar los ejemplos contra los tests HTTP y aclarar que registrar no inicia sesión.

## 5. Verificar la integración completa

- [ ] 5.1 Ejecutar `./mvnw verify` con Docker disponible y revisar que se ejecuten las pruebas nuevas y las existentes, incluidas migración, PostgreSQL y `LocalUserConfigurationTest`, sin fallos ni omisiones. Mantener nombres `*Test` para que Surefire las descubra; no dar por validada la integración con una ejecución que omita Docker.
- [ ] 5.2 Revisar el cambio final contra cada escenario de la especificación: V1 intacta, única migración nueva, credenciales fuera de respuestas/logs, fixtures limitados a Testcontainers y contratos existentes conservados. Ejecutar `openspec validate add-user-registration --strict` y comprobar que el README, diseño, especificación y comportamiento implementado concuerden.

## Estado de verificación

El código y las pruebas de todas las tareas están escritos y compilan. Las tareas que requieren comprobaciones
en PostgreSQL permanecen pendientes hasta poder ejecutarlas; no hay omisiones aceptadas del contrato.

- Pasan 43 pruebas sin Docker: `RegistrationRequestTest` (21), `RegistrationControllerTest` (20) y `LocalUserConfigurationTest` (2).
- `./mvnw verify` compila toda la suite y ejecuta esas 43 pruebas correctamente, pero `AuthFlowTest` y `UserNameMigrationTest` fallan al iniciar Testcontainers: el entorno deniega el acceso a `/var/run/docker.sock` (`Operation not permitted`).
- La política de aprobación rechaza solicitar ejecución fuera del aislamiento (`sandbox_approval: false`). Hace falta una sesión con acceso a Docker para completar la verificación.
- `openspec validate add-user-registration --strict` pasa. Se verificó que V1 y `LocalUserConfiguration` no cambiaron, que V2 es la única migración añadida y que el código de registro no registra credenciales.
