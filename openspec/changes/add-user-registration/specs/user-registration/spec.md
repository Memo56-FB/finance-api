# Spec Delta

## Purpose

Permitir que una persona cree una cuenta con nombre, email y contraseña validada y pueda utilizarla en la autenticación por sesión existente, sin exponer credenciales ni alterar cuentas previas.

## ADDED Requirements

### Requirement: Registro público de una cuenta

El sistema SHALL aceptar `POST /api/auth/register` con JSON `name`, `email` y `password`, sin autenticación previa y con CSRF válido. Un registro válido SHALL devolver `201 application/json` con únicamente `name` y `email` normalizados, sin redirección ni autenticación automática.

#### Scenario: Crear una cuenta con datos válidos
- **WHEN** una persona sin autenticar registra un email nuevo con nombre y contraseña válidos y un token CSRF válido
- **THEN** recibe `201` con únicamente el nombre y email guardados
- **AND** la cuenta queda habilitada y con el rol de usuario normal
- **AND** no se devuelve una cabecera de redirección

#### Scenario: El registro no sustituye al login
- **WHEN** la persona consulta `GET /api/auth/me` con la misma sesión anónima después de registrarse
- **THEN** recibe `401` hasta completar el login existente

### Requirement: Nombre obligatorio y acotado

El sistema SHALL quitar espacios exteriores del nombre y exigir un valor no nulo, no blanco y de 1 a 100 caracteres tras normalizarlo. SHALL conservar mayúsculas, acentos y espacios interiores sin imponer una expresión regular de nombres. Un nombre inválido SHALL causar `400` sin crear la cuenta.

#### Scenario: Conservar un nombre internacional normalizado
- **WHEN** se registra un email nuevo con `name` igual a `"  María José O'Neill  "` y los demás datos válidos
- **THEN** se guarda y devuelve `"María José O'Neill"`

#### Scenario: Rechazar un nombre ausente o vacío
- **WHEN** se envía por separado `name` omitido, `null`, `""` o un valor compuesto solo por espacios
- **THEN** se devuelve `400` con un error asociado a `name` y no se crea la cuenta

#### Scenario: Verificar los límites del nombre
- **WHEN** se prueban nombres normalizados de 1, 100 y 101 caracteres con los demás datos válidos
- **THEN** los dos primeros se aceptan y el de 101 devuelve `400` con un error de `name`

### Requirement: Email válido y normalizado

El sistema SHALL exigir email no nulo, no blanco, con formato de dirección de correo válido y un máximo de 254 caracteres después de quitar espacios exteriores y convertirlo a minúsculas de forma independiente de la configuración regional. Un email inválido SHALL causar `400` sin crear la cuenta.

#### Scenario: Normalizar el email antes de guardar
- **WHEN** se registra `"  Maria@Example.com  "` con datos válidos
- **THEN** la cuenta y la respuesta usan `"maria@example.com"`

#### Scenario: Rechazar email ausente, vacío o mal formado
- **WHEN** se envía por separado email omitido, `null`, `""`, solo espacios, `"maria"`, `"maria@"` o `"maria example.com"`
- **THEN** se devuelve `400` con un error de `email` sin crear la cuenta

#### Scenario: Verificar el tamaño máximo del email
- **WHEN** se prueban direcciones con formato válido de 254 y 255 caracteres después de normalizarlas
- **THEN** la de 254 se acepta y la de 255 devuelve `400` con un error de `email`

### Requirement: Contraseña validada sin transformaciones

El sistema SHALL exigir contraseña no nula, no blanca, de al menos 8 caracteres y como máximo 72 bytes UTF-8. SHALL rechazar incumplimientos con `400` antes de guardar y conservar exactamente la contraseña recibida, incluidos espacios exteriores, sin truncarla ni imponer reglas de composición adicionales.

#### Scenario: Verificar el mínimo de 8 caracteres
- **WHEN** se prueban contraseñas ASCII no blancas de 7 y 8 caracteres con los demás campos válidos
- **THEN** la de 7 devuelve `400` con un error de `password` y la de 8 permite el registro

#### Scenario: Rechazar contraseña ausente o blanca
- **WHEN** se envía por separado `password` omitido, `null`, `""` o ocho espacios
- **THEN** se devuelve `400` con un error de `password` sin crear la cuenta

#### Scenario: Distinguir caracteres de bytes UTF-8
- **WHEN** se prueban contraseñas de 72 y 73 caracteres ASCII y contraseñas de 36 y 37 repeticiones de `é`
- **THEN** se aceptan los dos valores de 72 bytes y se rechazan los de 73 y 74 bytes con `400`

#### Scenario: Conservar espacios significativos de la contraseña
- **WHEN** se registra una contraseña válida con espacios exteriores y luego se intenta iniciar sesión
- **THEN** la contraseña exacta permite el login y su versión recortada no lo permite

### Requirement: Email único sin sobrescritura

El sistema SHALL identificar las cuentas por email normalizado y devolver `409` cuando ya esté registrado, incluso si está deshabilitado o hay registros simultáneos. SHALL conservar intactos el nombre, la contraseña, el estado y las autoridades de la cuenta existente.

#### Scenario: Rechazar duplicados equivalentes
- **WHEN** existe `maria@example.com` y se intenta registrar ese email o `"  MARIA@EXAMPLE.COM  "` con otros datos
- **THEN** se devuelve `409` en ambos casos sin modificar la cuenta existente

#### Scenario: No reemplazar una cuenta deshabilitada
- **WHEN** se intenta registrar el email de una cuenta deshabilitada
- **THEN** se devuelve `409` y la cuenta continúa deshabilitada, con sus datos originales

#### Scenario: Resolver registros simultáneos
- **WHEN** dos solicitudes válidas intentan registrar simultáneamente el mismo email normalizado
- **THEN** una devuelve `201` y la otra `409`
- **AND** queda una sola cuenta completa, con el nombre y contraseña de la solicitud ganadora

### Requirement: Protección CSRF del registro

El sistema SHALL exigir en el registro el token obtenido desde `GET /api/auth/csrf`, enviado en la cabecera indicada y con la misma sesión. Un token ausente o inválido SHALL causar `403` antes de crear la cuenta, aunque el endpoint sea público.

#### Scenario: Utilizar el token de la sesión anónima
- **WHEN** un cliente obtiene el token desde `/api/auth/csrf` y lo envía con la misma sesión y datos de registro válidos
- **THEN** el registro puede completarse sin autenticación previa

#### Scenario: Rechazar un token ausente o inválido
- **WHEN** se intenta registrar una cuenta con datos válidos sin token CSRF o con un token inválido
- **THEN** se devuelve `403` y no se crea la cuenta

### Requirement: Errores de registro seguros y utilizables

El sistema SHALL devolver `400` por validación o JSON ilegible y `409` por email duplicado como `application/problem+json`, con `status`, `title` y `detail`. Los errores de validación SHALL incluir `errors` con `field` y `message`. Respuestas y logs SHALL excluir contraseñas, hashes y valores rechazados; las respuestas SHALL excluir detalles internos de excepciones.

#### Scenario: Identificar campos inválidos sin repetir sus valores
- **WHEN** un registro con CSRF válido contiene campos inválidos
- **THEN** el problema incluye los campos afectados y mensajes de validación, sin valores rechazados, contraseña ni hash

#### Scenario: Rechazar un cuerpo ilegible
- **WHEN** se envía JSON mal formado, cuerpo vacío o el literal JSON `null` con CSRF válido
- **THEN** se devuelve `400 application/problem+json` con un mensaje genérico, sin repetir el cuerpo ni detalles del parser
- **AND** no se crea la cuenta

#### Scenario: Informar un conflicto sin revelar datos de la cuenta
- **WHEN** un email ya registrado causa un conflicto
- **THEN** se devuelve `409 application/problem+json` sin datos de la cuenta existente ni mensajes de la base de datos

### Requirement: Persistencia segura y atómica

El sistema SHALL guardar una transformación unidireccional de la contraseña compatible con el login existente. SHALL persistir nombre, email, credencial, estado habilitado y rol normal como una única operación: un fallo SHALL dejar la cuenta sin crear, sin usuarios o autoridades parciales.

#### Scenario: Guardar una credencial que permita autenticar
- **WHEN** finaliza correctamente un registro
- **THEN** la credencial persistida no coincide con el texto de la contraseña y permite verificar la contraseña original
- **AND** están persistidos el nombre normalizado, email, estado habilitado y una única autoridad de usuario normal

#### Scenario: Revertir un fallo durante la creación
- **WHEN** falla la persistencia de nombre o autoridades después de iniciar la creación del usuario
- **THEN** la solicitud no devuelve éxito y no quedan registros parciales del email solicitado
- **AND** el fallo no se presenta como un email duplicado si no lo es

### Requirement: Compatibilidad con cuentas y autenticación existentes

El sistema SHALL permitir que las cuentas nuevas usen el login actual y conservar la autenticación de las cuentas previas aunque no tengan nombre. SHALL mantener `/api/auth/me` con su respuesta de email, las protecciones de sesión y CORS existentes y la política actual del usuario inicial local.

#### Scenario: Registrarse e iniciar sesión con el contrato actual
- **WHEN** una persona se registra y luego envía email y contraseña como formulario a `/api/auth/login` con CSRF válido
- **THEN** el login responde `204` y `/api/auth/me` responde `200` con el email normalizado, sin contraseña

#### Scenario: Conservar cuentas previas al soporte de nombres
- **WHEN** se actualiza una base con cuentas existentes y una de ellas inicia sesión
- **THEN** mantiene sus credenciales, estado y autoridades y puede autenticarse sin haber proporcionado nombre

#### Scenario: Mantener el origen permitido del frontend
- **WHEN** se realiza el preflight del registro desde el origen configurado y desde un origen ajeno
- **THEN** solo el origen configurado recibe autorización CORS con credenciales
