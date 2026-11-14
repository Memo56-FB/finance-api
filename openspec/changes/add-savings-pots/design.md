# Design

## Context

Ver `proposal.md` para la motivación y `specs/savings-pots/spec.md` para el contrato. Este diseño se incluye por las operaciones monetarias concurrentes, la migración y el borrado con vencimiento.

Estado observado:

- Java 25, Spring Boot 4.1.2, Maven Wrapper, Spring MVC, Bean Validation, JDBC, Flyway y Jackson 3; no hay JPA ni entidades financieras.
- `users.username` es la clave primaria normalizada que identifica al usuario de la sesión. No existe un `users.id` ni una columna de saldo del usuario.
- V1–V3 dejan `users` sin `authorities`. `SecurityConfiguration` autentica sin roles, mantiene CSRF y solo permite actualmente `GET`, `POST` y `OPTIONS` en CORS.
- El advice existente cubre exclusivamente `AuthController`; no resolverá los errores del nuevo controller.
- Las pruebas usan MockMvc, `@MockitoBean` y PostgreSQL 18 con Testcontainers y `@ServiceConnection`. Surefire ejecuta las clases `*Test` mediante `./mvnw verify`.
- La propuesta antigua `add-user-registration` aún describe autoridades ya eliminadas. Se conserva como contexto histórico de ese cambio; esta funcionalidad utiliza el código actual.

Decisiones confirmadas por el usuario:

- El saldo del usuario es derivado de apartados y de futuros presupuestos; los abonos/retiros cambian el saldo de un apartado y, con ello, su contribución al total.
- Borrar un apartado con saldo está permitido: ese saldo deja de contarse sin generar un gasto.
- El plazo para deshacer es de **10 segundos**.

Supuestos concretos para esta primera versión: metas e importes de dos decimales, una misma unidad monetaria sin conversiones, saldo no negativo, meta superable, nombres repetidos permitidos y color RGB `#RRGGBB`. No se introduce un límite de cantidad de apartados.

## Goals / Non-Goals

**Goals:**

- Encapsular el caso de uso en una funcionalidad hermana de `auth`, con SQL explícito y DTOs pequeños.
- Hacer que la propiedad del dato y el estado activo formen parte de cada operación de persistencia.
- Serializar las mutaciones del mismo apartado para proteger saldo, borrado y restauración con una única estrategia de concurrencia.
- Obtener listado y contribución al saldo a partir de la misma lectura, evitando totales persistidos que puedan desincronizarse.

**Non-Goals:**

- No introducir una capa genérica de repositorios, JPA, eventos, un scheduler o infraestructura nueva.
- No crear un saldo global en `users` ni contratos ficticios para presupuestos futuros.
- No transformar los apartados en un libro contable o sistema bancario. El historial de operaciones y la deduplicación de solicitudes monetarias quedan fuera de esta primera versión.

## Decisions

### 1. Una tabla propia vinculada únicamente al usuario existente

Agregar `V4__create_savings_pots.sql`; si apareciera otra V4 antes de implementar, utilizar la siguiente versión libre sin renumerar migraciones aplicadas.

| Columna | Tipo y regla |
| --- | --- |
| `id` | `BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY` |
| `username` | `VARCHAR(254) NOT NULL REFERENCES users(username)`; sin borrado en cascada |
| `name` | `VARCHAR(100) NOT NULL`; texto no vacío y sin espacios ASCII exteriores como defensa de persistencia |
| `target_amount` | `NUMERIC(19,2) NOT NULL`; entre `0.01` y `99999999999999999.99` |
| `balance` | `NUMERIC(19,2) NOT NULL DEFAULT 0`; entre cero y `99999999999999999.99` |
| `color` | `VARCHAR(7) NOT NULL`; patrón `^#[0-9A-F]{6}$` |
| `deleted_at` | `TIMESTAMPTZ NULL`; `NULL` significa activo |

Los límites monetarios se imponen también mediante `CHECK` con límite inferior y superior finitos; no basta `balance >= 0`, porque PostgreSQL admite `NaN` en `numeric`. El endpoint valida antes de persistir para impedir el redondeo automático de entradas con más de dos decimales. Usar `BigDecimal` y `NUMERIC`, sin `float`, `double` ni `money`. [Tipos numéricos de PostgreSQL](https://www.postgresql.org/docs/18/datatype-numeric.html).

Agregar un índice `(username, id) WHERE deleted_at IS NULL` para el listado por dueño. No agregar índice único por usuario o por nombre: un usuario tiene varios apartados y puede repetir sus nombres. No almacenar porcentajes, totales globales, `restore_until` ni un booleano adicional de borrado; son datos derivados.

Usar la clave de usuario actual evita migrar toda la autenticación a un identificador nuevo. Crear presupuestos o transacciones para sostener los apartados introduciría dependencias que el usuario decidió posponer.

### 2. Contratos HTTP pequeños y en inglés

Todos los endpoints requieren sesión. Los cuerpos de creación/edición contienen `name`, `targetAmount` y `color`. Los abonos/retiros contienen únicamente `amount`. Ningún DTO permite controlar dueño, saldo inicial o `deletedAt` desde el cliente.

| Método y ruta | Cuerpo | Éxito |
| --- | --- | --- |
| `GET /api/savings-pots` | — | `200` con `{ "items": [...], "totalBalance": ... }` |
| `GET /api/savings-pots/{id}` | — | `200` con el apartado activo propio |
| `POST /api/savings-pots` | Metadatos | `201` con el apartado y `Location` al detalle |
| `PUT /api/savings-pots/{id}` | Los tres metadatos completos | `200` con el apartado actualizado |
| `POST /api/savings-pots/{id}/deposits` | `{ "amount": 25.15 }` | `200` con el apartado actualizado |
| `POST /api/savings-pots/{id}/withdrawals` | `{ "amount": 10.00 }` | `200` con el apartado actualizado |
| `DELETE /api/savings-pots/{id}` | — | `200` con `id`, `deletedAt`, `restoreUntil` |
| `POST /api/savings-pots/{id}/restore` | — | `200` con el apartado activo |

La representación activa es `{ "id": 1, "name": "Emergency fund", "targetAmount": 1500.00, "balance": 35.25, "color": "#AABBCC" }`. Los importes se serializan como números decimales JSON desde `BigDecimal` y las fechas de borrado como instantes ISO-8601 UTC.

Usar `PUT` permite compartir el mismo DTO validado entre creación y edición. No agregar `PATCH` ni permitir editar directamente `balance`: el dinero entra o sale a través de las operaciones explícitas. `DELETE` devuelve `200` con el plazo para que el cliente pueda ofrecer deshacer sin depender de su propio reloj para autorizarlo.

### 3. Validación en la entrada y errores propios de la funcionalidad

- `name`: aplicar `strip()`, `@NotBlank` y `@Size(max = 100)`; conservar acentos y espacios interiores. Mantener la semántica UTF-16 que usa el registro actual.
- `targetAmount` y `amount`: `@NotNull`, mínimo `0.01`, máximo monetario y `@Digits(integer = 17, fraction = 2)` sobre `BigDecimal`. No redondear ni convertir pasando por `double`.
- `color`: quitar espacios exteriores, convertir a mayúsculas con `Locale.ROOT` y validar los seis dígitos hexadecimales precedidos por `#`; no añadir un catálogo de temas.
- `id`: entero `long` positivo; errores de conversión, cero y negativos causan `400`.
- Campos adicionales como `username` o `balance` no se vinculan a escrituras. Conservar el comportamiento de campos desconocidos de la aplicación; nunca usarlos para elegir dueño o modificar dinero.

Crear un advice limitado al controller de apartados, siguiendo `RegistrationExceptionHandler`: `ProblemDetail` para `400`, `404` y `409`, con `errors` de campo para validación y mensajes controlados para conflictos. No ampliar el advice de `auth` ni crear un envelope de errores distinto. Mantener los `401`/`403` del filtro de seguridad.

### 4. Separación por funcionalidad y responsabilidades

Introducir `dev.memofb.finance.savings`, manteniendo el patrón de paquetes por funcionalidad:

- `SavingsPotController`: rutas, entrada validada y usuario obtenido de `Principal`; sin SQL ni cálculo de saldos.
- `SavingsPotService`: reglas de saldo y estado, reloj y transacciones de las mutaciones.
- `SavingsPotRepository`: consultas JDBC parametrizadas y mapeo de filas; una clase concreta basta para las múltiples lecturas y escrituras de este caso de uso.
- Records en archivos propios para `SavingsPotRequest`, `MoneyAmountRequest`, `SavingsPotResponse`, `SavingsPotListResponse` y `SavingsPotDeletionResponse`, más el estado persistido necesario para `deletedAt`.
- `SavingsPotExceptionHandler` y excepciones pequeñas para no encontrado y conflicto, sin una jerarquía genérica.
- Una configuración pequeña del reloj en la misma funcionalidad si hace falta registrar `Clock.systemUTC()`; sustituible por un reloj controlado en pruebas.

No crear interfaces de repositorio, mappers genéricos ni subpaquetes por cada clase. Los DTOs separados permiten reconocer el contrato sin tener que buscar records dentro del servicio; se conserva el agrupamiento por funcionalidad de `AGENTS.md`.

### 5. Saldo derivado de una única lectura

El listado obtiene todos los apartados activos del usuario mediante una consulta ordenada por `id ASC`. El servicio suma sus `balance` con `BigDecimal` para devolver `totalBalance` desde el mismo conjunto de filas; no hace una segunda lectura del total ni carga datos de otros usuarios.

La respuesta representa exclusivamente la contribución de apartados. Un futuro cálculo global podrá combinar esa contribución con presupuestos cuando se definan, sin mantener dos copias del saldo hoy. La suma de múltiples apartados puede superar el máximo individual y se conserva con `BigDecimal`, sin forzarla a `NUMERIC(19,2)`.

Se elige un listado completo para el alcance actual, sin importar filtros o paginado del pedido de transacciones abandonado. Tampoco se crea una tabla `overview`, un endpoint de saldo global, caché ni vista materializada.

### 6. Una transacción corta y un bloqueo por apartado para cada mutación

En edición, abono, retiro, borrado y restauración, abrir una transacción de servicio con `@Transactional` y cargar la fila mediante `SELECT ... WHERE id = ? AND username = ? FOR UPDATE`, incluyendo su estado de borrado. Si no existe para ese dueño, responder `404`; después aplicar las reglas correspondientes y actualizar la misma fila con SQL parametrizado y dueño en el predicado.

Adquirir el bloqueo antes de leer el saldo y antes de comprobar estado/plazo. Mantener el aislamiento habitual `READ COMMITTED` y no hacer llamadas externas dentro de la transacción. El bloqueo se mantiene hasta commit/rollback y serializa las mutaciones sobre la fila. [Bloqueos de PostgreSQL](https://www.postgresql.org/docs/18/explicit-locking.html). Invocar los métodos transaccionales desde el controller mediante el bean de Spring, sin self-invocation que eluda el proxy. [Transacciones de Spring](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html).

El servicio suma/resta exactamente sobre el saldo bloqueado, rechaza fondos insuficientes o resultado fuera de rango con `409` y solo devuelve éxito después de confirmar la transacción. La creación utiliza un `INSERT` que determina el dueño desde la sesión y fija el saldo cero. Consultas normales de lista/detalle filtran también `deleted_at IS NULL`.

Se elige bloqueo de fila porque deben coordinarse saldo, edición, borrado y plazo de restauración con errores distintos. Un `SELECT` sin bloqueo seguido de `UPDATE` perdería cambios; añadir versionado optimista y reintentos introduciría otro protocolo que no es necesario aquí.

### 7. Borrado reversible sin proceso en segundo plano

| Estado | Lectura normal | Edición/abonos/retiros | Borrado | Restauración |
| --- | --- | --- | --- | --- |
| Activo (`deleted_at IS NULL`) | Visible | Permitidos | Fija `deletedAt` | `200`, sin cambios |
| Borrado y `now < deletedAt + 10s` | `404` / excluido del listado | `404` | Devuelve las fechas originales | Limpia `deletedAt` |
| Borrado y `now >= deletedAt + 10s` | `404` / excluido del listado | `404` | Devuelve las fechas originales | `409`, sin cambios |

Al borrar, guardar un instante UTC del backend y conservar nombre, meta, color y saldo; calcular `restoreUntil` desde el `deletedAt` efectivamente persistido. No modificar `balance`, insertar gastos ni redistribuir dinero. Repetir `DELETE` no amplía la ventana. Restaurar limpia únicamente `deletedAt`; su contribución vuelve a contarse sin ejecutar un nuevo abono.

Evaluar el instante del reloj **después de obtener el bloqueo**. Una restauración enviada antes de vencer que espera bloqueada hasta después del plazo debe fallar. El reloj del frontend no autoriza restauraciones. La comparación estricta rechaza el instante exacto de vencimiento; la fecha persistida evita ampliar el plazo tras reinicios. Mantener el reloj inyectable permite probar estos límites sin pausas.

No se necesita scheduler: vencer el plazo cambia la posibilidad de restaurar, no elimina físicamente la fila. Los registros borrados quedan retenidos e inaccesibles por la API normal; una política de purga será independiente.

### 8. Mantener el perímetro de seguridad existente

No agregar endpoints públicos ni reintroducir roles. Todo acceso se obtiene de `Principal.getName()` y se aplica en la consulta SQL, sin aceptar `username` por path, query o cuerpo como autoridad para operar.

Agregar `PUT` y `DELETE` a la lista CORS de `SecurityConfiguration`, manteniendo el origen explícito, credenciales y cabeceras existentes. No desactivar CSRF: creación, edición, depósitos, retiros, borrado y restauración requieren el token obtenido con `/api/auth/csrf`.

Para pruebas de autorización en escrituras, usar CSRF válido y así aislar el `401` por ausencia de sesión. La falta de CSRF conserva el `403` actual y no debe confundirse con las reglas de propiedad.

### 9. Verificación enfocada en dinero, aislamiento y plazo

| Área | Evidencia prevista |
| --- | --- |
| DTOs con Validator real | Nombre normalizado y límites; color; importe nulo/no positivo/fuera de rango/con exceso de decimales; precisión y entradas Unicode. |
| Slice HTTP con seguridad real | Contratos y estados, `Location`, JSON inválido, problemas seguros, campos protegidos, sesión y CSRF para cada escritura, métodos CORS nuevos. |
| PostgreSQL/Testcontainers | CRUD y propiedad, cantidad no limitada por una regla de negocio, decimal exacto, abonos y retiros con rechazo sin cambios, total desde activos y exclusión de otros usuarios. |
| Estado y reloj controlado | Borrado con saldo, datos conservados, fechas originales tras repetir DELETE, restauración a 9 segundos, en el límite y fuera del plazo, repetición de restore, fecha original tras recrear el servicio. |
| Concurrencia real | Abonos sin pérdida, retiros que compiten por fondos, borrado frente a mutación monetaria y restauración que debe comprobar el reloj tras esperar un bloqueo. Coordinar con latches y límites de espera. |
| Migración y regresiones | Base vacía y base en V3 con usuarios existentes; restricciones de la tabla; suite de autenticación actual intacta mediante `./mvnw verify`. |

Las pruebas usan exclusivamente el PostgreSQL del contenedor y datos sintéticos. No modificar la base de desarrollo, añadir una base H2 ni envolver pruebas de concurrencia en una transacción exterior que esconda el commit.

## Risks / Trade-offs

- [Saldo global aún incompleto] → `totalBalance` se documenta como suma de apartados activos. Los presupuestos y su fórmula global se definirán después, sin almacenar un total ficticio ahora.
- [Borrar con saldo quita ese dinero del total sin gasto] → Es la regla confirmada. Conservar el saldo y habilitar diez segundos de restauración; después la API no lo recupera.
- [Sin historial ni deduplicación de abonos/retiros] → Cada solicitud monetaria exitosa representa una operación nueva; un reenvío puede aplicarse otra vez. Documentarlo y evitar reintentos automáticos del cliente. Si se incorporan movimientos de dinero real o reintentos fiables, definir antes un historial con identificadores de operación e idempotencia.
- [Listado completo y filas borradas retenidas] → Mantener un índice de activos y registrar la limitación; introducir paginado o purga cuando el volumen o las reglas de retención lo justifiquen, sin imponer un máximo de apartados ahora.
- [Ventana de diez segundos frente a latencia/relojes] → Publicar `restoreUntil`, validar con reloj UTC del servidor tras el bloqueo y probar el límite. Varias instancias deberán tener relojes sincronizados.
- [Metas sin moneda explícita y JSON decimal] → Este cambio usa una sola unidad monetaria de dos decimales, sin conversiones. El cliente debe conservar precisión decimal al operar con importes, especialmente fuera del rango exacto de sus tipos numéricos.
- [El usuario se identifica por email] → Reutilizar la clave existente y no introducir cambios de email ni borrado en cascada de datos financieros en este cambio.

## Migration Plan

1. Crear la migración aditiva V4 y el código de la funcionalidad como una unidad, conservando V1–V3.
2. Probar arranque desde base vacía y migración desde V3 con usuarios existentes; verificar que no cambian usuarios ni credenciales.
3. Ejecutar `./mvnw verify` con Docker y revisar el contrato documentado antes de desplegar. No hacer commit hasta que el usuario lo solicite después de revisar.
4. En el despliegue, Flyway crea la tabla antes de servir solicitudes. No se insertan apartados de ejemplo ni movimientos para usuarios reales.
5. Si se revierte la aplicación, conservar `savings_pots`: el código anterior no la utiliza. No borrar la tabla ni los saldos como parte del rollback; cualquier cambio posterior de esquema será una migración nueva.
