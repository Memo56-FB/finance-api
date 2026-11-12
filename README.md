# financeApi

Backend mínimo de financeApp con Java 25, Spring Boot 4.1.2 y paquete base `dev.memofb.finance`.
Autenticación por correo y contraseña con sesiones de Spring Security, contraseñas BCrypt y usuarios en PostgreSQL.

## Desarrollo local

Requisitos: JDK 25 y Docker con Compose. Maven se descarga mediante el wrapper.

```bash
cp .env.example .env
```

Edita `.env`: configura una contraseña para PostgreSQL y el correo y contraseña de tu primer usuario.
La contraseña del usuario debe tener al menos 12 caracteres y como máximo 72 bytes UTF-8, por el límite de BCrypt.
Conserva las comillas simples para que el archivo también pueda cargarse en la terminal.

```bash
set -a
. ./.env
set +a
docker compose up -d --wait
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

La API escucha en `http://localhost:8080`. Flyway crea las tablas `users` y `authorities` al iniciar.
La migración V2 agrega el nombre del usuario. Las cuentas anteriores y el usuario inicial local pueden
conservar el nombre vacío (`NULL`); el endpoint de registro exige un nombre para las cuentas nuevas.
El perfil `local` crea el primer usuario si todavía no existe; reiniciar no cambia su contraseña.
Ese perfil permite cookies por HTTP y solo escucha en la interfaz local.
Fuera de `local`, las cookies requieren HTTPS y no se crea ningún usuario automáticamente.

Spring Security proporciona el almacenamiento JDBC y el flujo de autenticación. Las funcionalidades nuevas irán
en paquetes hermanos de `auth`, dentro de `dev.memofb.finance`, según se necesiten.

## Contrato de autenticación

El registro requiere `name`, `email` y `password`. El nombre se recorta en sus extremos y debe tener de 1 a 100
caracteres; conserva acentos y espacios interiores. El email se recorta, convierte a minúsculas y valida como
dirección de correo, con un máximo de 254 caracteres y sin duplicados. La contraseña requiere al menos **8
caracteres** y como máximo **72 bytes UTF-8**; no se recorta ni se exigen reglas adicionales de composición.
Las longitudes de texto siguen las unidades UTF-16 de Java; el límite de la contraseña se mide en bytes aparte.
El mínimo de 12 caracteres descrito para desarrollo local corresponde únicamente al usuario inicial de ese perfil.

| Método | Endpoint | Resultado |
| --- | --- | --- |
| GET | `/api/auth/csrf` | `200` con `token`, `headerName` y `parameterName`; disponible antes del login. |
| POST | `/api/auth/register` | JSON con `name`, `email` y `password`; `201` con nombre y email normalizados. No inicia sesión. |
| POST | `/api/auth/login` | Formulario con `email` y `password`; `204` si son correctos, `401` si no. |
| GET | `/api/auth/me` | `200` con `{ "email": "..." }`; `401` sin sesión. |
| POST | `/api/auth/logout` | Invalida la sesión y elimina la cookie; responde `204`. |

Los POST requieren el token CSRF en el encabezado indicado por `/api/auth/csrf`.
Un token ausente o inválido devuelve `403`. Los errores de autenticación no devuelven detalles sobre la cuenta.
Login usa `application/x-www-form-urlencoded`; las respuestas de login y logout no tienen cuerpo ni redirección.

Registro usa `application/json`. Una respuesta correcta es
`{ "name": "María José", "email": "maria@example.com" }`, sin contraseña ni hash.
Los datos inválidos o un cuerpo JSON ilegible devuelven `400`; un email ya registrado, incluso de una cuenta
deshabilitada, devuelve `409` y conserva la cuenta existente. Ambos usan `application/problem+json` con `status`,
`title` y `detail`. Los errores de validación incluyen `errors`, por ejemplo
`[{ "field": "password", "message": "La contraseña debe tener al menos 8 caracteres" }]`, sin repetir el valor
rechazado. El registro también devuelve `403` si falta CSRF o es inválido. La cuenta, su nombre y su rol se
guardan en una sola transacción; la contraseña se guarda con el codificador BCrypt existente.

El frontend debe usar `credentials: 'include'` en todas las llamadas y obtener un nuevo token CSRF después del
login o logout. CORS permite únicamente `http://localhost:3000`; usa `FINANCE_FRONTEND_ORIGIN` si cambia ese origen.
La integración con el formulario de `financeFront` queda para el siguiente cambio.

Ejemplo de CSRF → registro → login para ejecutar con un email nuevo en la consola del navegador,
desde `http://localhost:3000`:

```javascript
const api = 'http://localhost:8080'
const csrf = await fetch(`${api}/api/auth/csrf`, {
  credentials: 'include',
}).then(response => response.json())

const name = prompt('Nombre')
const email = prompt('Correo')
const password = prompt('Contraseña')
const registration = await fetch(`${api}/api/auth/register`, {
  method: 'POST',
  credentials: 'include',
  headers: {
    'Content-Type': 'application/json',
    [csrf.headerName]: csrf.token,
  },
  body: JSON.stringify({ name, email, password }),
})
if (!registration.ok) throw new Error(`Registro rechazado: ${registration.status}`)
console.log(registration.status, await registration.json())

const login = await fetch(`${api}/api/auth/login`, {
  method: 'POST',
  credentials: 'include',
  headers: { [csrf.headerName]: csrf.token },
  body: new URLSearchParams({
    email: email.trim().toLowerCase(),
    password,
  }),
})
console.log(login.status)

const me = await fetch(`${api}/api/auth/me`, { credentials: 'include' })
console.log(await me.json())
```

Las sesiones viven en memoria y se pierden al reiniciar. Antes de publicar la API, configura límites de intentos
de login y registro en el proxy; para múltiples instancias, usa un almacenamiento compartido de sesiones.

## Pruebas

```bash
./mvnw verify
```

Ejecuta pruebas unitarias de validación y creación del usuario local, pruebas HTTP del registro y pruebas de
integración con un PostgreSQL independiente en Testcontainers. Las pruebas de integración requieren Docker y
cubren migración de cuentas existentes, registro, duplicados, concurrencia, rollback, autenticación, sesión,
CSRF, CORS, credenciales incorrectas y cuentas deshabilitadas. No utilizan ni borran la base de desarrollo.

## Skills

Las cuatro skills de Spring están instaladas en `.agents/skills`. Inicia Codex desde `financeApi` para que se
detecten automáticamente en el próximo turno. La fuente y revisión están registradas en `.agents/skills/SOURCES.md`.
