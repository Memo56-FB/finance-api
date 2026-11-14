# Tasks

## 1. Persistencia de apartados

- [x] 1.1 Agregar `V4__create_savings_pots.sql` con las columnas, relación a `users`, restricciones monetarias finitas y de texto/color e índice de activos del diseño; verificar con PostgreSQL que crea la tabla tanto desde una base vacía como desde V3 y preserva las cuentas existentes.
- [x] 1.2 Crear `SavingsPotRepository` con JDBC parametrizado para inserción, lectura activa por dueño, listado ordenado, bloqueo de fila por dueño y actualizaciones; verificar en Testcontainers el aislamiento, la exclusión de borrados y el rechazo de FK inválida, saldo negativo, `NaN`, meta no positiva y color inválido.
- [x] 1.3 Documentar en el README el esquema, la relación al usuario y la retención de borrados sin purga; verificar que los identificadores coinciden con la migración y que no se crean tablas de presupuestos, categorías ni transacciones.

## 2. Consultas, creación y edición

- [x] 2.1 Crear los DTOs en inglés para metadatos, representación del apartado y listado, con normalización y Bean Validation; verificar con pruebas parametrizadas nombre, Unicode, color, campos omitidos/nulos, meta y límites monetarios sin redondeo.
- [x] 2.2 Implementar `SavingsPotService` para crear con saldo cero, leer, listar con suma exacta de activos y editar metadatos bajo transacción y bloqueo; verificar con PostgreSQL múltiples apartados, nombres repetidos, total vacío/correcto, suma superior al máximo individual y conservación del saldo al editar o bajar la meta.
- [x] 2.3 Agregar `SavingsPotController` para `GET` de lista/detalle, `POST` de creación y `PUT` de edición, obteniendo el dueño de `Principal`; verificar con MockMvc `200`/`201`, `Location`, datos devueltos, campos protegidos y `404` para inexistentes, ajenos o borrados.
- [x] 2.4 Crear el advice de la funcionalidad para validación, JSON ilegible, identificadores inválidos y no encontrado; verificar `application/problem+json`, errores por campo, ausencia de valores rechazados/SQL y que los cuerpos inválidos no ejecutan escrituras.
- [x] 2.5 Ampliar CORS únicamente con `PUT` y `DELETE`, manteniendo origen explícito y CSRF; verificar sesión, `401` con CSRF válido sin login, `403` sin CSRF y preflights permitidos/rechazados. Documentar en README los cuatro contratos implementados y la semántica de `totalBalance`.

## 3. Abonos y retiros

- [x] 3.1 Crear `MoneyAmountRequest` y los endpoints `POST /{id}/deposits` y `POST /{id}/withdrawals`; verificar entrada positiva de máximo dos decimales, rango, nulos, cero, negativos, JSON no numérico/no finito y estado `400` sin cambios ante entradas inválidas.
- [x] 3.2 Implementar abonos/retiros exactos dentro de la transacción del servicio y sobre el saldo bloqueado; verificar con PostgreSQL precisión `0.10 + 0.20`, meta superable, retiro completo, rechazo `409` por fondos insuficientes/desbordamiento, y `404` para ajenos o borrados sin cambios persistidos.
- [x] 3.3 Probar abonos y retiros concurrentes con conexiones/transacciones independientes y latches: verificar ausencia de cambios perdidos, que solo uno de dos retiros incompatibles tiene éxito y que nunca se obtiene saldo negativo; limitar las esperas del test.
- [x] 3.4 Documentar cuerpos, respuestas y conflictos de ambos endpoints, su efecto en `totalBalance` y la semántica de solicitudes repetidas; verificar en MockMvc autenticación y CSRF para ambas operaciones y cotejar los ejemplos del README con los tests.

## 4. Borrado y restauración durante diez segundos

- [x] 4.1 Registrar un reloj UTC inyectable e implementar `DELETE /{id}` con borrado lógico y respuesta `SavingsPotDeletionResponse`; verificar fechas persistidas, conservación del saldo/metadatos y desaparición inmediata del listado, detalle y total, sin insertar gastos ni ejecutar un retiro.
- [x] 4.2 Implementar `POST /{id}/restore` usando la fecha persistida y el reloj después de adquirir el bloqueo; verificar con reloj controlado restauración antes del límite, rechazo `409` en el instante exacto y después de diez segundos, estado original recuperado y plazo conservado al recrear el servicio.
- [x] 4.3 Cubrir repeticiones y carreras: verificar que `DELETE` repetido no extiende el plazo, restore repetido no duplica saldo, un abono concurrente con borrado queda serializado y una restauración que espera un bloqueo comprueba el vencimiento tras obtenerlo; usar coordinación y tiempo controlado sin sleeps arbitrarios.
- [x] 4.4 Verificar con MockMvc el aislamiento por dueño, la protección CSRF de DELETE/restore y el cuerpo de sus errores; documentar en README la ventana de diez segundos, la posibilidad de borrar con dinero, el efecto sobre el total y la retención posterior sin endpoint de recuperación tardía.

## 5. Verificación integrada

- [x] 5.1 Agregar un flujo HTTP real con sesión y token de `/api/auth/csrf` que cree, consulte, edite, abone, retire, borre y restaure un apartado; verificar saldo y total después de cada paso, repetir consultas con un segundo usuario y comprobar que no observa ni modifica datos ajenos.
- [x] 5.2 Ejecutar `./mvnw verify` con Docker y `git diff --check`; verificar que pasan las pruebas nuevas y las regresiones de registro, login, cookies/sesión, CSRF, CORS, migraciones y cuentas deshabilitadas, y que solo se modificó el alcance previsto.

## Workflow follow-up

- Presentar los cambios implementados y su validación al usuario para revisión; no crear commits hasta que lo solicite explícitamente.
- La integración del saldo con presupuestos, el historial de movimientos y las políticas de paginado o purga requieren cambios posteriores; no añadirlos para completar estas tareas.
