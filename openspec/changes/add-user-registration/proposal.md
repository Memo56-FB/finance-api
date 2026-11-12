# Proposal

## Why

La API permite iniciar sesión con usuarios existentes, pero no ofrece un registro público ni almacena su nombre. El usuario necesita crear su cuenta con nombre, email y una contraseña de al menos 8 caracteres, con validaciones y pruebas del flujo completo.

## What Changes

- Agregar `POST /api/auth/register` con JSON `name`, `email` y `password`, disponible sin autenticación y protegido por el CSRF existente.
- Validar nombre obligatorio de 1 a 100 caracteres después de quitar espacios exteriores; email obligatorio, con formato válido y máximo de 254 caracteres después de normalizarlo; contraseña obligatoria de mínimo 8 caracteres y máximo de 72 bytes UTF-8, compatible con el BCrypt actual.
- Responder `201` con nombre y email normalizados, sin iniciar sesión automáticamente; devolver `400` por datos inválidos y `409` por email ya registrado, sin exponer credenciales.
- Guardar el nombre mediante una nueva migración Flyway; crear usuario habilitado, contraseña codificada y autoridad `ROLE_USER` en una sola transacción, preservando las cuentas existentes.
- Cubrir validaciones, límites, duplicados, atomicidad, CSRF y registro seguido de login con pruebas unitarias, HTTP y PostgreSQL.
- Documentar el contrato y distinguir el mínimo de 8 del registro del mínimo de 12 que actualmente exige el usuario inicial del perfil `local`.

El alcance comprende el backend. Quedan fuera frontend, verificación de correo, recuperación de contraseña, login automático, cambios al contrato de `/api/auth/me` y cambios a la política del usuario inicial local.

## Capabilities

### New Capabilities

- `user-registration`: registro de cuentas mediante nombre, email y contraseña; validación, unicidad, persistencia segura y compatibilidad con la autenticación por sesión.

### Modified Capabilities

Ninguna. El proyecto todavía no contiene capacidades en `openspec/specs/`.

## Impact

- `auth`: ampliar `AuthController` y el permiso puntual en `SecurityConfiguration`; agregar DTO de registro, servicio transaccional y manejo de errores, reutilizando `JdbcUserDetailsManager`, `PasswordEncoder` y JDBC.
- PostgreSQL: nueva migración `V2__add_user_name.sql`; conservar `V1__create_users.sql`, claves de email y autoridades existentes. Permitir nombre ausente en cuentas anteriores y en el bootstrap local.
- `pom.xml`: agregar el starter de Bean Validation administrado por Spring Boot; mantener Java 25, Boot 4.1.2 y las dependencias de prueba actuales.
- Pruebas: ampliar `AuthFlowTest`, agregar pruebas rápidas del registro y verificar que `LocalUserConfigurationTest` siga pasando. Usar los fixtures y PostgreSQL de Testcontainers existentes, sin tocar la base de desarrollo.
- `README.md`: incorporar solicitud/respuesta, errores, CSRF, límites y flujo registro → login. Verificación de implementación mediante `./mvnw verify` con Docker.
