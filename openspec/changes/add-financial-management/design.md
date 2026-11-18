# Design

## Context

Ver [proposal.md](proposal.md) para la motivación. El proyecto usa Spring Boot 4.1.2, Java 25, JDBC y PostgreSQL 18; no usa JPA. La identidad persistida es `users.username`. Ya existen sesiones, CSRF, validación con records, errores `ProblemDetail`, Flyway V1–V4 y Swagger habilitado solo con el perfil `local`.

`SavingsPotService` mantiene `savings_pots.balance`, bloquea el apartado antes de ajustarlo y ofrece restauración estrictamente anterior a diez segundos. Su lista suma únicamente apartados activos. No existe aún saldo global, registro de movimientos, categorías, presupuestos ni automatización. Los tests usan PostgreSQL real mediante Testcontainers y un `Clock` reemplazable.

No hay especificaciones publicadas en `openspec/specs`; el cambio anterior `add-savings-pots` está implementado, pero sigue sin archivarse. Este cambio no modifica sus documentos ni su contrato HTTP.

## Goals / Non-Goals

**Goals:** mantener una única fuente para cálculos financieros; hacer atómicas las operaciones que afectan varias tablas; conservar los datos de apartados existentes; dejar campos y códigos suficientes para traducir la UI sin guardar etiquetas traducidas.

**Non-Goals:** libro contable de partida doble, conexión bancaria, monedas múltiples, infraestructura de colas, cachés, repositorios genéricos y tablas de contadores derivados. Se reutilizan dependencias y patrones instalados.

## Decisions

### 1. Registro de transacciones como fuente de los totales del corte

Una transacción es efectiva si `transactions.deleted_at IS NULL` y, cuando tiene `pot_id`, el apartado sigue activo. Todas las consultas financieras reutilizan exactamente esta condición. No se excluye un gasto histórico porque su definición recurrente o su categoría hayan sido eliminadas.

- `income = SUM(amount WHERE amount > 0)`.
- `expenses = SUM(-amount WHERE amount < 0)`.
- `currentBalance = income - expenses`.

Los tres agregados se restringen al periodo actual del usuario mediante `transaction_date >= :periodStart AND transaction_date < :periodEndExclusive`. `currentBalance` es el neto de ese corte y no arrastra saldo de cortes anteriores. El historial sigue mostrando todas las fechas con su paginado; el filtro del resumen no elimina ni mueve filas. Presupuestos y resúmenes de pagos mantienen sus propios meses calendario.

Se usan `BigDecimal` y agregados `NUMERIC`; una suma no se limita al máximo de una sola fila. No hay PUT de totales ni saldos iniciales editables: un ingreso inicial se registra como una transacción positiva. Un cambio de corte no requiere reiniciar contadores, crear movimientos de apertura ni programar otro job.

La preferencia contiene `frequency=MONTHLY|SEMIMONTHLY` y `startDay=1..31`. El usuario confirmó solo esas dos frecuencias y cortes ligados al calendario; no se aceptan duraciones libres ni bloques continuos de exactamente treinta días. El valor por defecto es `MONTHLY` desde el día 1. Se guarda una sola preferencia por usuario y se resuelve ese valor por defecto sin escribir si todavía no existe.

Para obtener el periodo se calcula el ancla del día elegido en cada mes, recortándola al último día cuando no exista. Se toma el ciclo entre dos anclas consecutivas que contiene hoy. En `MONTHLY` se usa todo ese ciclo; en `SEMIMONTHLY`, se divide en el ancla más quince días: el primer tramo dura quince días y el segundo ocupa el resto, de trece a dieciséis días. Se conserva siempre el día elegido al calcular el ancla del mes siguiente.

| Configuración | Ejemplos de periodos inclusivos |
| --- | --- |
| `MONTHLY`, día 4 | 4 de enero–3 de febrero; 4 de febrero–3 de marzo |
| `SEMIMONTHLY`, día 1 | 1–15 y 16–31 de enero; 1–15 y 16–28 de febrero de 2027 |
| `SEMIMONTHLY`, día 4 | 4–18 de marzo y 19 de marzo–3 de abril |
| `MONTHLY`, día 31 | 31 de enero–27 de febrero; 28 de febrero–30 de marzo de 2027 |

El GET devuelve `periodStart` y `periodEnd` inclusivos para la UI; internamente se convierte el final en exclusivo para evitar doble conteo. Se captura hoy una sola vez desde el `Clock` y la zona de negocio, y se leen preferencia y agregados en la misma instantánea. PUT de la preferencia aplica inmediatamente a consultas posteriores; no cambia transacciones, saldos de apartados, presupuestos ni pagos. No se almacena historial de preferencias ni se introduce una consulta adicional de resúmenes históricos en esta versión.

**Alternativa descartada:** guardar saldo/ingresos/gastos por usuario y actualizarlos desde cada servicio. Añadiría contadores que pueden divergir al editar, borrar o restaurar movimientos.

**Alternativas de corte descartadas:** bloques exactos de quince/treinta días que desplazan el día de inicio, y un tercer tramo para el día 31. El usuario eligió cortes de calendario mensuales y quincenales; el último tramo se ajusta al mes.

### 2. Modelo persistido y pertenencia

Una nueva migración `V5__add_financial_management.sql` crea las seis tablas en orden de dependencia. No se editan V1–V4. Las cinco tablas de movimientos y definiciones tienen `id BIGINT` identity y `username` con referencia a `users`; la preferencia usa directamente `username` como clave primaria y foránea, garantizando una por usuario.

| Tabla | Datos persistidos principales | Restricciones |
| --- | --- | --- |
| `categories` | `name`, `deleted_at` | Nombre normalizado, no vacío, máximo 100; sin límite de cantidad |
| `budgets` | `category_id`, `spending_limit`, `color`, `deleted_at` | Importe positivo; color hexadecimal; un presupuesto activo por usuario/categoría |
| `recurring_payments` | `name`, `category_id`, `type`, `amount`, `first_due_date`, `total_installments`, `next_auto_sequence`, `deleted_at` | `SUBSCRIPTION` sin total; `INSTALLMENT` con total positivo; importe por cuota positivo |
| `recurring_payment_occurrences` | `recurring_payment_id`, `sequence_number`, `due_month`, `due_date`, `status`, `paid_at` | Secuencia positiva; un registro por definición/secuencia y por definición/mes; estado `PAID` o `VOID` |
| `transactions` | `name`, `category_id`, `transaction_date`, `amount`, `source_type`, `pot_id`, `recurring_occurrence_id`, `opening_balance`, `deleted_at` | Importe firmado no cero; origen coherente; a lo sumo un gasto activo por ocurrencia |
| `financial_summary_preferences` | `username`, `frequency`, `start_day` | Propietario como clave primaria; frecuencia `MONTHLY` o `SEMIMONTHLY`; día entero entre 1 y 31 |

La tabla de preferencias no contiene importes ni fechas de periodos calculados. GET devuelve el valor persistido o el defecto sin crear filas; PUT hace upsert bajo el bloqueo del propietario. No se necesita modificar el registro de usuarios ni insertar una fila de preferencias durante la migración.

`due_month` es el primer día del mes y coincide con el mes de `due_date`. Una ocurrencia se crea al primer registro de pago; un pendiente sin actividad se deriva de su definición, sin materializar infinitos meses de una suscripción. `VOID` conserva una corrección explícita y evita que el proceso automático la deshaga.

Las referencias de categoría, apartado, definición y ocurrencia incluyen `(username, id)`, respaldadas por claves únicas compuestas, para impedir asociaciones entre usuarios incluso en persistencia. Se añade la clave compuesta necesaria a `savings_pots`, sin cambiar sus valores. No se usa borrado en cascada de datos financieros.

Los orígenes válidos son `MANUAL` (categoría obligatoria y sin referencias de origen), `POT` (apartado obligatorio, categoría opcional) y `RECURRING_PAYMENT` (ocurrencia y categoría obligatorias). El índice único parcial de `transactions(recurring_occurrence_id)` aplica únicamente a filas no borradas con importe negativo. Así una corrección positiva puede conservar su procedencia sin impedir un nuevo pago manual explícito.

Índices por usuario/fecha/ID para historial, usuario/categoría/fecha para gastos mensuales, referencias de origen y categorías/presupuestos/definiciones activos. No se añade un motor de búsqueda: búsqueda parcial parametrizada sobre el volumen personal esperado. Las restricciones se basan en [claves únicas y foráneas de PostgreSQL](https://www.postgresql.org/docs/18/ddl-constraints.html).

**Alternativa descartada:** una tabla de saldo y una categoría global traducida `Pots`. Duplicarían datos y mezclarían traducciones con categorías privadas.

### 3. Categorías e históricos

Los nombres creados por el usuario son texto libre; se normalizan con `strip()` como los apartados. No se exige unicidad de nombre: el ID es la identidad. El borrado lógico oculta la categoría para nuevas asignaciones. Si existe un presupuesto o definición recurrente activa que la referencia, DELETE devuelve 409 y pide editar o eliminar ese dependiente primero.

Las transacciones históricas conservan ID y nombre legible. Un PUT puede mantener su categoría inactiva actual; si selecciona otra, debe estar activa y pertenecer al usuario. Las categorías virtuales de apartados se representan con `sourceType`, `potId` y `potName`; `categoryId=null` significa que no se asignó una categoría personal. El frontend puede pintar una etiqueta traducida sin analizar un nombre como `pots (Travel)`.

**Alternativa descartada:** borrar referencias o transacciones junto con la categoría. Cambiaría el saldo al organizar el catálogo.

### 4. Integración con apartados conservando su regla actual

Un abono crea un movimiento positivo; un retiro crea uno negativo. Cada uno contribuye al resumen solo si su fecha pertenece al corte consultado. No se modela como transferencia desde otro saldo ni se crea una contrapartida adicional.

`savings_pots.balance` se conserva como proyección para mantener el contrato actual. Actualizar el balance y crear el movimiento ocurre en una misma transacción de base de datos. El importe, nombre, fecha y categoría de movimientos generados siguen siendo editables: para un movimiento de apartado, cambiar el importe ajusta su balance en `newAmount - oldAmount`; borrarlo ajusta en `-oldAmount`. Se valida el mismo rango de balance vigente: de cero al máximo NUMERIC(19,2). Un global negativo está permitido; un apartado negativo sigue rechazándose con 409.

El origen no se acepta en cuerpos de creación o edición. Un movimiento puede cambiar de signo sin perder su apartado original; el signo determina su efecto actual. Su nombre inicial es una copia del nombre del apartado y su fecha la fecha de negocio de la operación. Cambiar después el nombre del apartado no reescribe movimientos ya editables.

DELETE del apartado solamente establece `deleted_at`. Su historial vinculado deja de ser efectivo y desaparece de la lista de transacciones; no se inserta un gasto para compensarlo. Restaurar antes de diez segundos recupera las filas no borradas individualmente. Los movimientos de apartados ocultos devuelven 404 y no se pueden modificar hasta restaurarlos. Se mantienen las respuestas y ocho rutas actuales de `SavingsPotController`.

El balance persistido del apartado acumula sus movimientos, aunque el resumen del corte solo sume un subconjunto por fecha. Por ejemplo, un abono de 100 en un corte anterior y un retiro de 20 en el actual dejan el apartado en 80 y el resumen actual en -20 sin otro movimiento. Cambiar de corte no reinicia ese balance. Al borrar/restaurar el apartado cambian únicamente las contribuciones que pertenecen al corte del resumen, sin mover importes entre fechas.

**Alternativa descartada:** sumar el balance del apartado además de sus transacciones. Contaría dos veces el mismo dinero. Registrar el borrado como gasto también contradice la regla confirmada.

### 5. Contratos HTTP y consultas

Todos los nombres de código, tablas, JSON y enums son ingleses. La propiedad procede siempre del `Principal`; no existe `username` editable en un request.

| Recurso | Operaciones |
| --- | --- |
| `/api/categories` | GET lista activa, POST crear; GET/PUT/DELETE `/{id}` |
| `/api/transactions` | GET historial, POST crear; GET/PUT/DELETE `/{id}` |
| `/api/financial-summary` | GET del resumen del corte actual, con fechas inclusivas |
| `/api/financial-summary/settings` | GET de la preferencia personal y PUT de reemplazo completo |
| `/api/budgets` | GET lista con cálculos mensuales, POST crear; GET/PUT/DELETE `/{id}` |
| `/api/recurring-payments` | GET lista activa del mes, POST crear; GET/PUT/DELETE `/{id}` |
| `/api/recurring-payments/summary` | GET de cantidades e importes mensuales |
| `/api/recurring-payments/{id}/payments/{month}` | PUT sin cuerpo: registrar como pagado el mes `YYYY-MM`, con resultado idempotente |

POST devuelve 201 y `Location`; GET y PUT, 200; los nuevos DELETE, 204. No se altera el DELETE actual de apartados. Falta de sesión conserva el comportamiento 401; falta de CSRF, 403; ID inexistente/ajeno, 404; datos o query inválidos, 400; conflicto de dominio, 409. `ProblemDetail` mantiene errores de campo sin SQL ni detalles internos.

GET/PUT de preferencias devuelve `{frequency, startDay}`; PUT recibe esos dos campos obligatorios, rechaza frecuencias distintas y días fuera de 1–31 y conserva la configuración anterior si falla. El propietario procede de la sesión y el PUT requiere CSRF. No hay DELETE de preferencias. GET del resumen devuelve `{periodStart, periodEnd, currentBalance, income, expenses}` para el periodo que contiene hoy, sin aceptar importes editables. Por ejemplo, `{frequency: "SEMIMONTHLY", startDay: 1}` produce cortes 1–15 y 16–fin de mes; el frontend traduce los enums.

El cuerpo de una transacción es `{name, categoryId, transactionDate, amount}`. Su respuesta añade `id`, `sourceType`, `potId`, `potName`, `recurringPaymentId`, `occurrenceId` y `openingBalance`, usando null donde no corresponda. Las respuestas POST/PUT incluyen `budgetWarnings: []` junto a los campos de la transacción, y el marcado de pago devuelve la ocurrencia, su transacción y esos avisos.

El cuerpo de presupuesto es `{categoryId, spendingLimit, color}`. Sus lecturas añaden `month`, `spent`, `available`, `limitReached` y `exceededAmount`. El cuerpo recurrente es `{name, categoryId, type, amount, firstDueDate, totalInstallments}`. Sus lecturas añaden `billingDay`, el vencimiento del mes solicitado, estado de ese mes, `paidInstallments` y `totalInstallments` (null para suscripción). La UI construye el nombre con `3/12`; la base conserva el nombre sin sufijo.

Historial y pagos usan `search`, `page=0`, `size=20` (1–100) y `sort=newest`. Los órdenes permitidos son `newest`, `oldest`, `name_asc`, `name_desc`, `amount_desc`, `amount_asc`. La búsqueda es por fragmento literal del nombre, sin distinguir mayúsculas; se escapan `%`, `_` y el carácter de escape. Se parametrizan valores y se selecciona ORDER BY desde un enum permitido, con ID como desempate. Importes de transacción se ordenan con signo. Las fechas de pagos se ordenan por vencimiento del mes seleccionado.

Las dos listas paginadas devuelven `{items, page, size, totalElements, totalPages}`; la página fuera de rango contiene `items=[]`. Catálogos y presupuestos devuelven `{items}` sin añadir un límite de negocio. `month` es opcional en GET de presupuestos y pagos; por defecto usa el mes de negocio actual. Sus resúmenes no dependen de la búsqueda ni de la página.

**Alternativa descartada:** un endpoint por orden o un endpoint global que devuelva todas las tablas. Las consultas tienen finalidades distintas; un GET de resumen puede agregar varios importes sin multiplicar peticiones del frontend.

### 6. Presupuestos derivados, con avisos no bloqueantes

Cada presupuesto es una definición mensual repetida por categoría. La edición cambia la definición para cualquier mes consultado; no se versionan límites históricos. Se agrega una sola consulta de gastos por categoría y mes para la página/lista necesaria, evitando una consulta por transacción o presupuesto.

Se consideran solo importes negativos efectivos; ingresos no reponen presupuesto. `available` puede ser negativo y `limitReached` es verdadero también en igualdad. `exceededAmount` es cero en igualdad y positivo al superar. Los avisos contienen `{budgetId, categoryId, month, limitReached, exceededAmount}` y se calculan después de la mutación dentro de su transacción. Un PUT que cambia categoría o mes revisa los pares afectados. Una operación correcta devuelve éxito aunque haya exceso.

Los abonos/retiros actuales no requieren una categoría y conservan sus respuestas; solo participan en presupuestos cuando el movimiento tiene una categoría personal. Los pagos automáticos exponen el exceso en el siguiente GET; no hay notificaciones push. Eliminar un presupuesto no toca movimientos ni saldo.

**Alternativa descartada:** persistir `available` y una bandera de límite. Serían derivados que habría que corregir en cada edición, borrado o restauración.

### 7. Ocurrencias, correcciones e idempotencia de pagos

La secuencia 1 pertenece al mes de `firstDueDate`; cada mes siguiente incrementa uno. La fecha se obtiene del día ancla, recortado al último día cuando corresponda. El importe es por cuota. Se usa la misma función de calendario para lista, resumen y procesamiento.

Un pago manual o automático bloquea el propietario y la definición, verifica que el periodo existe y crea o consulta su ocurrencia. Si está `PAID`, devuelve el pago existente. En otro caso inserta un gasto con el nombre, categoría e importe vigentes, y deja la ocurrencia `PAID` con `paid_at` UTC. El automático fecha el gasto con `due_date`; el manual con la fecha de negocio actual. Un pago anticipado se cuenta como pagado en su mes de vencimiento, aunque el gasto afecte al presupuesto del mes en que se registró y al resumen financiero del corte que contiene su fecha. Recuperar un vencimiento atrasado fuera del corte actual no lo incorpora a los totales de ese corte.

Editar una transacción negativa vinculada mantiene `PAID`; su importe, fecha y categoría actuales son los que se muestran y calculan, sin actualizar la definición de futuras cuotas. Eliminar ese gasto o cambiarlo a positivo deja la ocurrencia `VOID` y limpia `paid_at`; una positiva sigue siendo ingreso con su origen legible. El automático omite `VOID`. Solo un nuevo PUT manual explícito puede crear un gasto de reemplazo, tomando la configuración vigente. El gasto previo borrado permanece histórico, y un ingreso corregido previo sigue siendo ingreso. No hay resurrección automática de una corrección del usuario.

El estado se resuelve desde el gasto negativo activo de la ocurrencia: editar o eliminar un ingreso de origen anterior no invalida un gasto de reemplazo. Volver a cambiar ese ingreso a negativo restaura `PAID` si no existe otro gasto activo; si existe uno, devuelve 409 y revierte todo. El índice parcial impide dos gastos activos incluso ante una carrera.

Se cuentan cuotas realmente pagadas, no meses transcurridos. Para una definición activa, los meses sin pago o `VOID` son pendientes; para una cancelada no se generan pendientes. Los pagos históricos con gasto activo permanecen en el resumen de su mes aun cuando la definición esté cancelada. Borrar la definición no borra sus gastos.

Tras la primera ocurrencia no puede cambiar el primer mes de la serie. Cambiar el día de `firstDueDate` dentro de ese mismo mes ajusta los vencimientos aún no registrados; una ocurrencia ya registrada conserva su fecha. No se reduce `totalInstallments` por debajo de la mayor secuencia registrada. Cambiar tipo/importe/categoría no reescribe el histórico. Una cuota atrasada que nunca fue registrada usa la configuración vigente al recuperarse.

**Alternativa descartada:** inferir pagos por el nombre o por los meses transcurridos, o volver a generar cualquier gasto borrado. Ninguna permite pagos anticipados, correcciones y reintentos fiables.

### 8. Automatización local al proceso y calendario explícito

Se reutiliza el `Clock` existente; los instantes se guardan en UTC. La propiedad `finance.business-zone`, por defecto `America/Mexico_City`, determina hoy y el mes de negocio de forma idéntica en local y nube. El calendario de transacciones suministradas es `LocalDate`, sin conversiones de zona. El valor por defecto es una decisión para esta primera versión, no una preferencia por usuario.

El requerimiento confirmado de marcar pagos al vencimiento autoriza un proceso programado de Spring. Una configuración dedicada habilita `@EnableScheduling`; un `@Scheduled` con `fixedDelay` de 60 segundos procesa lotes de como máximo 100 ocurrencias por ejecución. No se añade dependencia ni servicio externo. `finance.recurring-payments.scheduling-enabled=false` permite desactivarlo en tests y operación. Ver [scheduling de Spring](https://docs.spring.io/spring-framework/reference/integration/scheduling.html).

`next_auto_sequence`, inicialmente 1, avanza al procesar o encontrar una ocurrencia `PAID`/`VOID` y permite recuperar atrasos en orden sin escanear todo el histórico. Un pago manual futuro no adelanta este cursor: todavía podrían faltar periodos anteriores. Se vuelve a validar el candidato bajo bloqueo; después se avanza el cursor en la misma transacción que el pago. Al llegar a una fecha futura o al total de cuotas se detiene esa serie. Los candidatos se recorren en páginas para que no queden series sin revisar por tener IDs altos.

Un fallo revierte esa ocurrencia y se registra sin credenciales; las demás continúan y la fallida se reintenta después. Varios procesos pueden encontrar el mismo candidato, pero el bloqueo y las restricciones únicas garantizan un solo efecto financiero. Un GET no escribe pagos. Durante una caída no se ejecuta el job; al volver recupera atrasos, en lugar de prometer ejecución exacta a medianoche.

**Alternativas descartadas:** cron externo, Quartz, broker y bloqueo distribuido adicional. El trabajo entero consiste en cambios atómicos en PostgreSQL; los bloqueos e idempotencia existentes en ese mismo recurso bastan.

### 9. Concurrencia y límites de los servicios

Cada mutación financiera adquiere primero un bloqueo `FOR UPDATE` de la fila `users` del propietario. Después adquiere los bloqueos de apartado, definición u ocurrencia necesarios en un orden fijo. Abarca transacciones, apartados, presupuestos, categorías, pagos y preferencias de corte; sustituye el orden aislado actual de los apartados. Serializa las escrituras de una persona y evita carreras entre reclasificación, borrado de categorías, avisos y el job sin bloquear a otros usuarios.

Se usa `@Transactional` en entradas invocadas a través del proxy; el scheduler llama a otro bean para obtener una transacción independiente por ocurrencia. Para lecturas con conteo y página o varios agregados, una transacción de solo lectura `REPEATABLE_READ` o una única consulta mantiene la misma instantánea. Nunca se calculan importes en memoria desde una página incompleta.

Se conservan paquetes por funcionalidad (`categories`, `transactions`, `summary`, `budgets`, `recurring`, además de `savings`). Requests y responses son records próximos a su controller, igual que ahora. Los servicios coordinan reglas y los repositorios JDBC contienen SQL. Se exponen únicamente los métodos de repositorio requeridos entre funcionalidades; los servicios no se inyectan recíprocamente. El bloqueo del propietario es una utilidad pequeña compartida; no se añade un framework de eventos ni interfaces con una sola implementación.

El alcance incluye los repositorios y servicios de apartados, sus tests y fixtures; los nuevos DTOs/controllers/servicios/repositorios, incluidas preferencias y resolución del corte en `summary`; la configuración del calendario/job; pruebas de integración de auth y Swagger; y README. `RegistrationService`, hashing y tablas de autenticación no requieren cambios de comportamiento.

**Alternativa descartada:** contadores optimistas, eventos asíncronos y bloqueos independientes sin orden. Añadirían coordinación y resultados intermedios innecesarios para una aplicación financiera personal.

## Risks / Trade-offs

- **Saldo negativo global frente a apartado no negativo:** se conserva la regla ya implementada del apartado. Un PUT/DELETE de su movimiento puede devolver 409; el usuario puede corregir el historial sin dejar una proyección inválida.
- **Borrar un apartado reduce tanto su ingreso como su gasto históricos:** se excluyen sus movimientos para retirar exactamente su contribución neta sin inventar un gasto. El frontend puede explicar el borrado; restaurar recupera las contribuciones originales.
- **Saldos heredados sin historial:** el backfill solo conoce el saldo actual; crea un movimiento `openingBalance=true` fechado al migrar. No afirma reconstruir ingresos/retiros anteriores.
- **Saldo del corte frente a balance acumulado del apartado:** `currentBalance` es el neto del periodo, no el patrimonio histórico. Las fechas explícitas evitan interpretarlo como arrastre y los apartados conservan sus balances al pasar de corte; los movimientos de apertura solo contribuyen al corte de su fecha.
- **Quincena con longitud variable:** el segundo tramo ajusta los días sobrantes o faltantes del ciclo mensual. Mostrar `periodStart` y `periodEnd` en la UI; no anunciar siempre quince o treinta días exactos.
- **Automatización de pagos:** registra gastos aunque el usuario no haya confirmado un cobro real y puede retrasarse durante una caída. Es la regla solicitada de registro; el histórico puede corregirse y no vuelve a cobrarse automáticamente.
- **Un bloqueo por usuario:** limita escrituras simultáneas de esa persona. Mantener transacciones cortas y lotes acotados; los demás usuarios tienen bloqueos independientes.
- **Búsqueda por fragmento y catálogos completos:** suficientes para el volumen esperado, sin límites de negocio nuevos. Medir antes de añadir índices especializados o paginación a otros recursos.
- **Fechas y límites mensuales editables:** los límites históricos usan la definición actual; no se promete versionado ni conciliación bancaria. La zona de negocio debe configurarse igual en todas las réplicas.

## Migration Plan

1. Probar Flyway tanto desde una base vacía como desde V4 poblada con usuarios y apartados activos/borrados. Verificar claves de propietario, signos, rangos, índices únicos, preferencias válidas/únicas y preservación de datos.
2. Con las escrituras detenidas durante el despliegue, crear V5 en orden: categorías, presupuestos, definiciones, ocurrencias, transacciones y preferencias; añadir la clave compuesta de apartados. Crear un movimiento `POT` con `opening_balance=true` por cada balance preexistente no cero, sin volver a modificar el balance. Los apartados borrados conservan su marca y su movimiento queda oculto. Los usuarios sin preferencia usan mensual día 1 sin backfill de configuración.
3. Desplegar los servicios que escriben a la vez proyección y registro, y habilitar el job después de que Flyway complete. Un usuario nuevo no necesita filas de totales; sus agregados ya devuelven cero.
4. Validar el flujo local con sesión/CSRF desde Swagger y ejecutar `./mvnw verify` con Docker. Las pruebas no relacionadas desactivan el job para evitar cambios por reloj; las dedicadas ejercitan su recuperación y concurrencia.
5. Si hay que revertir la aplicación, desactivar el job y detener escrituras financieras. Mantener V5 y los datos; volver al código antiguo permitiría cambios en apartados sin registro y provocaría divergencia. Antes de reactivar escrituras se requiere reconciliar proyección y movimientos o desplegar una corrección hacia delante. No borrar tablas ni reescribir migraciones aplicadas como rollback.
