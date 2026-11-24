# financeApi

Backend mínimo de financeApp con Java 25, Spring Boot 4.1.2 y paquete base `dev.memofb.finance`.
Autenticación por nombre de usuario y contraseña con sesiones de Spring Security, contraseñas BCrypt y usuarios en PostgreSQL.

## Desarrollo local

Requisitos: JDK 25 y Docker con Compose. Maven se descarga mediante el wrapper.

```bash
cp .env.example .env
```

En `.env`, configura la contraseña de PostgreSQL y las credenciales de tu primer usuario.
La contraseña del usuario debe tener al menos 12 caracteres y como máximo 72 bytes UTF-8, por el límite de BCrypt.
Conserva las comillas simples para que el archivo también pueda cargarse en la terminal.

```bash
set -a
. ./.env
set +a
docker compose up -d --wait
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

La API escucha en `http://localhost:8080`. Flyway prepara la tabla `users` al iniciar.
La migración V2 agrega el nombre del usuario y V3 elimina la antigua tabla `authorities`, conservando
las cuentas y sus credenciales. La autenticación no usa roles ni permisos. Las cuentas anteriores y el usuario inicial local pueden
conservar el nombre vacío (`NULL`); el endpoint de registro exige un nombre para las cuentas nuevas.
El perfil `local` crea el primer usuario si todavía no existe; reiniciar no cambia su contraseña.
En un entorno local existente, cambia `FINANCE_BOOTSTRAP_EMAIL` por `FINANCE_BOOTSTRAP_USERNAME` en
`.env` y usa el nombre migrado. Flyway V7 toma la parte anterior a `@`, sustituye cada grupo de caracteres
no admitidos por `_` y conserva los nombres válidos existentes. Si hay coincidencias, asigna los sufijos
`_2`, `_3`, etc., en orden del identificador anterior. Puedes consultar los nombres asignados con
`SELECT username, name FROM users ORDER BY username;`. Las contraseñas y los datos financieros se conservan.
Ese perfil permite cookies por HTTP y solo escucha en la interfaz local.
Fuera de `local`, las cookies requieren HTTPS y no se crea ningún usuario automáticamente.

Un `UserDetailsService` consulta las credenciales en PostgreSQL mediante JDBC y Spring Security proporciona el flujo de autenticación. Las funcionalidades nuevas irán
en paquetes hermanos de `auth`, dentro de `dev.memofb.finance`, según se necesiten.

## Probar la API con Swagger

Con el backend arrancado con el perfil `local`, abre `http://localhost:8080/swagger-ui.html`.
La ruta redirige a `http://localhost:8080/swagger-ui/index.html`. El arranque mediante Maven usa IPv4
para que el reenvío de `localhost` de Windows hacia WSL funcione conservando la escucha en `127.0.0.1`.
Si ejecutas el JAR directamente en WSL, usa `java -Djava.net.preferIPv4Stack=true -jar target/finance-api-0.0.1-SNAPSHOT.jar`
con `SPRING_PROFILES_ACTIVE=local` y tus variables habituales cargadas.
Swagger UI muestra los servicios de autenticación y apartados con **Try it out → Execute**.
La especificación OpenAPI está en `/v3/api-docs`. Se utiliza springdoc 3.2.0, compatible con Spring Boot 4.1
según su [matriz de compatibilidad](https://springdoc.org/faq.html#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot).

1. Ejecuta `POST /api/auth/login` con el nombre de usuario y la contraseña de una cuenta existente. Usa los campos del
   formulario que muestra Swagger; responde `204`. También puedes crear una cuenta con `/api/auth/register`.
2. Comprueba tu sesión con `GET /api/auth/me` y prueba los endpoints de apartados.
3. Ejecuta `POST /api/auth/logout` para cerrar la sesión.

Swagger obtiene un token nuevo de `/api/auth/csrf` antes de cada escritura y lo envía en su encabezado,
incluidos login y logout. La cookie de sesión se conserva en el navegador; no necesitas copiar tokens,
introducir un bearer token ni integrar el frontend. Esta adaptación usa el interceptor de solicitudes
de [Swagger UI](https://swagger.io/docs/open-source-tools/swagger-ui/usage/configuration/).

Swagger y OpenAPI están deshabilitados por defecto y se habilitan en `local`, que escucha solo en la
interfaz local. Las operaciones de prueba modifican los datos de la base que tenga configurada tu backend;
CSRF y la autenticación de los servicios siguen activos.

## Contrato de autenticación

El registro requiere `name`, `username` y `password`. El nombre se recorta en sus extremos y debe tener de 1 a 100
caracteres; conserva acentos y espacios interiores. El nombre de usuario es único, se recorta y se guarda en minúsculas.
Admite de 1 a 254 caracteres ASCII entre letras, números, puntos, guiones y guiones bajos. La contraseña requiere al menos **8
caracteres** y como máximo **72 bytes UTF-8**; no se recorta ni se exigen reglas adicionales de composición.
Las longitudes de texto siguen las unidades UTF-16 de Java; el límite de la contraseña se mide en bytes aparte.
El mínimo de 12 caracteres descrito para desarrollo local corresponde únicamente al usuario inicial de ese perfil.

| Método | Endpoint | Resultado |
| --- | --- | --- |
| GET | `/api/auth/csrf` | `200` con `token`, `headerName` y `parameterName`; disponible antes del login. |
| POST | `/api/auth/register` | JSON con `name`, `username` y `password`; `201` con `name` y `username` normalizados. No inicia sesión. |
| POST | `/api/auth/login` | Formulario con `username` y `password`; `204` si son correctos, `401` si no. |
| GET | `/api/auth/me` | `200` con `{ "username": "..." }`; `401` sin sesión. |
| POST | `/api/auth/logout` | Invalida la sesión y elimina la cookie; responde `204`. |

Los POST requieren el token CSRF en el encabezado indicado por `/api/auth/csrf`.
Un token ausente o inválido devuelve `403`. Los errores de autenticación no devuelven detalles sobre la cuenta.
Login usa `application/x-www-form-urlencoded`; las respuestas de login y logout no tienen cuerpo ni redirección.

Registro usa `application/json`. Una respuesta correcta es
`{ "name": "María José", "username": "maria" }`, sin contraseña ni hash.
Los datos inválidos o un cuerpo JSON ilegible devuelven `400`; un nombre de usuario ya registrado, incluso de una cuenta
deshabilitada, devuelve `409` y conserva la cuenta existente. Ambos usan `application/problem+json` con `status`,
`title` y `detail`. Los errores de validación incluyen `errors`, por ejemplo
`[{ "field": "password", "message": "La contraseña debe tener al menos 8 caracteres" }]`, sin repetir el valor
rechazado. El registro también devuelve `403` si falta CSRF o es inválido. La cuenta y su nombre se
guardan con un único `INSERT` atómico; la contraseña se guarda con el codificador BCrypt existente.

El frontend debe usar `credentials: 'include'` en todas las llamadas y obtener un nuevo token CSRF después del
login o logout. CORS permite únicamente `http://localhost:3000`; usa `FINANCE_FRONTEND_ORIGIN` si cambia ese origen.
La integración con el formulario de `financeFront` queda para el siguiente cambio.

Ejemplo de CSRF → registro → login para ejecutar con un nombre de usuario nuevo en la consola del navegador,
desde `http://localhost:3000`:

```javascript
const api = 'http://localhost:8080'
const csrf = await fetch(`${api}/api/auth/csrf`, {
  credentials: 'include',
}).then(response => response.json())

const name = prompt('Nombre')
const username = prompt('Nombre de usuario')
const password = prompt('Contraseña')
const registration = await fetch(`${api}/api/auth/register`, {
  method: 'POST',
  credentials: 'include',
  headers: {
    'Content-Type': 'application/json',
    [csrf.headerName]: csrf.token,
  },
  body: JSON.stringify({ name, username, password }),
})
if (!registration.ok) throw new Error(`Registro rechazado: ${registration.status}`)
console.log(registration.status, await registration.json())

const login = await fetch(`${api}/api/auth/login`, {
  method: 'POST',
  credentials: 'include',
  headers: { [csrf.headerName]: csrf.token },
  body: new URLSearchParams({
    username,
    password,
  }),
})
console.log(login.status)

const me = await fetch(`${api}/api/auth/me`, { credentials: 'include' })
console.log(await me.json())
```

Flyway V8 crea las tablas de Spring Session. PostgreSQL conserva las sesiones autenticadas entre instancias y
reinicios de la API. La sesión autenticada no caduca por inactividad y el cierre de sesión la elimina. Al
desplegar V8 por primera vez, las sesiones anteriores, que estaban en memoria, requieren iniciar sesión de nuevo.
Antes de publicar la API, configura límites de intentos de login y registro en el proxy.

## Apartados de ahorro

Los apartados se guardan en `savings_pots`, creada por la migración V4 y vinculada a `users.username`.
Cada usuario puede tener múltiples apartados, incluso con nombres repetidos. Se almacenan `name`,
`target_amount`, `balance`, `color` y `deleted_at`; los importes usan `NUMERIC(19,2)` y el saldo inicial es cero.
Solo se consultan apartados activos propios. El borrado conserva los datos y su saldo, sin purga física
programada. Esta funcionalidad no crea tablas de presupuestos, categorías ni transacciones.

Todos los endpoints de apartados requieren la sesión existente. Las escrituras también requieren el token
CSRF obtenido de `/api/auth/csrf`. CORS permite `PUT` y `DELETE` desde el mismo origen frontend configurado.

| Método | Endpoint | Resultado |
| --- | --- | --- |
| GET | `/api/savings-pots` | `200` con `items` y `totalBalance`, ordenados por `id` ascendente. |
| GET | `/api/savings-pots/{id}` | `200` con el apartado activo propio. |
| POST | `/api/savings-pots` | `201` con el apartado nuevo, saldo cero y `Location` al detalle. |
| PUT | `/api/savings-pots/{id}` | `200` con los metadatos actualizados; conserva el saldo. |
| POST | `/api/savings-pots/{id}/deposits` | `200` con el saldo incrementado. |
| POST | `/api/savings-pots/{id}/withdrawals` | `200` con el saldo reducido. |
| DELETE | `/api/savings-pots/{id}` | `200` con `id`, `deletedAt` y `restoreUntil`. |
| POST | `/api/savings-pots/{id}/restore` | `200` con el apartado restaurado dentro del plazo. |

Creación y edición reciben los tres campos: `{ "name": "Emergency fund", "targetAmount": 1500.00,
"color": "#AABBCC" }`. El nombre se recorta y requiere de 1 a 100 unidades UTF-16; el color acepta seis
dígitos hexadecimales con `#` y se normaliza a mayúsculas. La meta es positiva, de máximo dos decimales,
hasta `99999999999999999.99`, y puede quedar por debajo del saldo existente. El cliente no elige el dueño
ni modifica directamente el saldo o el estado de borrado.

Cada apartado se representa como `{ "id": 1, "name": "Emergency fund", "targetAmount": 1500.00,
"balance": 0.00, "color": "#AABBCC" }`. `totalBalance` es la suma decimal exacta de los apartados activos
propios, no el saldo global con presupuestos futuros. Sin apartados activos devuelve `items: []` y total cero.
El listado no tiene paginado ni un máximo de apartados por usuario en esta versión.

Datos o identificadores inválidos devuelven `400`; identificadores inexistentes, ajenos o borrados devuelven
`404` en las consultas normales y edición. Los errores de aplicación usan `application/problem+json` con
`status`, `title` y `detail`; los de campos incluyen `errors` con `field` y `message`. Las consultas sin
sesión reciben `401`, y las escrituras sin CSRF válido `403`, sin revelar datos de otros usuarios.

Abonos y retiros reciben `{ "amount": 25.15 }`: importe positivo de máximo dos decimales, hasta el mismo
límite de la meta. Un abono puede superar la meta. Un retiro puede dejar el saldo en cero, pero nunca
negativo. Fondos insuficientes o un abono que excedería el saldo máximo devuelven `409` sin modificar datos.
El total aumenta o disminuye con el saldo de cada apartado. Las operaciones del mismo apartado se
serializan mediante un bloqueo de fila dentro de una transacción, para conservar cambios concurrentes.

Cada solicitud monetaria exitosa aplica una operación nueva: no hay historial ni deduplicación de
solicitudes en esta versión. Evita reintentos automáticos de abonos/retiros tras una respuesta incierta,
pues podrían aplicarse dos veces. Los números JSON deben conservar su precisión decimal en el cliente;
el backend utiliza `BigDecimal` para los cálculos y no hace conversiones de moneda.

Borrar un apartado con dinero está permitido: conserva sus datos y saldo, pero lo oculta y excluye su
saldo del total inmediatamente, sin registrar un gasto ni un retiro. La respuesta devuelve instantes UTC,
por ejemplo `{ "id": 1, "deletedAt": "2026-11-14T00:00:00Z", "restoreUntil": "2026-11-14T00:00:10Z" }`.
Puedes deshacer con `POST /api/savings-pots/1/restore` sin cuerpo, con sesión y CSRF válidos. La hora del
servidor, comprobada después de obtener el bloqueo de la fila, debe ser anterior a `restoreUntil`.
En el instante exacto del vencimiento o después devuelve `409`; el registro permanece retenido y oculto.

Repetir DELETE devuelve las fechas originales sin extender los diez segundos. Restaurar un apartado ya
activo devuelve su estado sin duplicar saldo. Al restaurar se recuperan el saldo y los metadatos originales
y se vuelve a incluir su contribución al total. Reiniciar el backend no reinicia el plazo. Mientras está
borrado, consultar el detalle, editar, abonar o retirar devuelve `404`; no existe recuperación tardía por API
ni un proceso que elimine físicamente estos registros.

## Registro financiero

Todas las rutas siguientes requieren sesión. Los métodos POST, PUT y DELETE necesitan el token CSRF
obtenido de `/api/auth/csrf`; Swagger local lo adjunta automáticamente. El propietario siempre se obtiene
de la sesión: los cuerpos no permiten seleccionar otro usuario. IDs ajenos devuelven `404`, campos o
parámetros inválidos `400`, conflictos de dominio `409` y falta de CSRF `403`.

| Recurso | Métodos |
| --- | --- |
| `/api/categories` | GET lista activa, POST crear |
| `/api/categories/{id}` | GET, PUT, DELETE |
| `/api/transactions` | GET paginado, POST crear |
| `/api/transactions/{id}` | GET, PUT, DELETE |
| `/api/financial-summary` | GET del corte actual |
| `/api/financial-summary/settings` | GET y PUT de la preferencia personal |
| `/api/budgets` | GET lista, POST crear |
| `/api/budgets/{id}` | GET, PUT, DELETE |
| `/api/recurring-payments` | GET paginado, POST crear |
| `/api/recurring-payments/{id}` | GET, PUT, DELETE |
| `/api/recurring-payments/summary` | GET del resumen mensual de pagos |
| `/api/recurring-payments/{id}/payments/{month}` | PUT sin cuerpo para marcar pagado `YYYY-MM` |

POST devuelve `201` y `Location`; GET/PUT, `200`; los nuevos DELETE, `204`. Los ocho servicios de
apartados conservan sus respuestas originales. No hay PUT/DELETE de totales financieros.

### Categorías y transacciones

POST/PUT de categorías recibe `{"name":"Groceries"}`. Los nombres se recortan, deben tener de 1 a 100
caracteres y no tienen que ser únicos. No hay un límite de categorías por usuario. DELETE oculta la
categoría, conserva sus referencias históricas y no cambia saldos. Si un presupuesto o pago recurrente
activo la utiliza, devuelve `409`: primero hay que editar o eliminar ese dependiente. Una transacción
histórica puede conservar su categoría inactiva; las nuevas asignaciones requieren una activa propia.

POST/PUT de transacciones recibe:

```json
{"name":"Groceries","categoryId":1,"transactionDate":"2027-03-20","amount":-45.50}
```

El importe positivo registra un ingreso; el negativo, un gasto. Se admiten saldos negativos: este backend
es un registro, no mueve dinero bancario. Los importes son decimales exactos, distintos de cero, de hasta
17 enteros y 2 decimales. No se redondean entradas con más decimales. Las fechas usan `YYYY-MM-DD`.

Las respuestas incluyen `sourceType`, `categoryName`, `potId`, `potName`, `recurringPaymentId`,
`occurrenceId`, `openingBalance` y `budgetWarnings`. Los campos de origen los establece el servidor.
GET devuelve los avisos vacíos; POST/PUT los calcula para los presupuestos afectados. Los cuatro campos
del cuerpo son editables también en movimientos automáticos, manteniendo inmutable su procedencia.
DELETE excluye el movimiento de cálculos e historial efectivo y conserva su vínculo de origen.

El historial y la lista de pagos admiten `search`, `page` (desde 0), `size` (1–100, por defecto 20) y `sort`:
`newest`, `oldest`, `name_asc`, `name_desc`, `amount_desc`, `amount_asc`. El orden por defecto es `newest`;
funciona con o sin búsqueda. La búsqueda es un fragmento de nombre sin distinguir mayúsculas y trata
`%`, `_` y `!` literalmente. Los empates se resuelven por ID. El importe de transacciones se ordena con
su signo, no por valor absoluto. Por ejemplo: `/api/transactions?search=groceries&sort=amount_asc&page=0&size=20`.
El filtro opcional `categoryId` acepta un ID positivo y se combina con `search` antes del conteo y la
paginación. Por ejemplo: `/api/transactions?categoryId=1&search=groceries&sort=newest&page=1&size=4`.
También permite consultar categorías inactivas con historial; una categoría inexistente o ajena devuelve
cero resultados. Omitir el filtro conserva el listado habitual.

La respuesta es `{items,page,size,totalElements,totalPages}`; una página posterior al final devuelve una
lista vacía. Las listas de categorías y presupuestos usan `{items}`.

### Cortes del resumen financiero

GET/PUT `/api/financial-summary/settings` devuelve/recibe:

```json
{"frequency":"SEMIMONTHLY","startDay":4}
```

Solo se aceptan `MONTHLY` y `SEMIMONTHLY`, y días de 1 a 31. Sin preferencia guardada se usa mensual
desde el día 1; consultar no crea una fila. El corte mensual desde el 4 va del 4 al 3 siguiente.
El quincenal desde el 1 usa 1–15 y 16–fin de mes; desde el 4 usa 4–18 y 19–3 siguiente. El primer tramo
dura quince días y el segundo se ajusta al ciclo mensual. Si falta el día elegido, se usa el último de ese
mes conservando el ancla: día 31 en febrero se recorta y vuelve al 31 en marzo. No hay tercer corte para el día 31.

GET `/api/financial-summary` devuelve `periodStart`/`periodEnd` inclusivos e `income`, `expenses` y
`currentBalance`, usando solo movimientos efectivos fechados dentro de ese corte. Income suma positivos,
expenses suma el valor absoluto de negativos y currentBalance es su diferencia. **CurrentBalance es el
neto del corte, sin arrastre histórico.** Un corte vacío devuelve ceros. Cambiar la preferencia recalcula
la consulta sin modificar movimientos, reiniciar apartados ni cambiar presupuestos o vencimientos.
No es necesario un job para reiniciar totales al cambiar de periodo.

`finance.business-zone` (variable `FINANCE_BUSINESS_ZONE`, por defecto `America/Mexico_City`) determina
hoy y el mes de negocio; los instantes se conservan en UTC y las fechas de movimientos no se convierten
de zona. Todas las réplicas deben usar la misma zona.

Cada abono/retiro de apartado genera una transacción positiva/negativa atómica. El frontend puede traducir
una categoría virtual usando `sourceType=POT` y `potId`, sin analizar textos traducidos; inicialmente no
tiene `categoryId` personal. Editar o borrar su importe ajusta el balance del apartado y devuelve `409` si
lo dejaría negativo o fuera de rango. Borrar/restaurar el apartado oculta/reactiva sus movimientos sin
crear un gasto compensatorio ni duplicarlos. Un apartado con abono de 100 en un corte anterior y retiro
de 20 en el actual conserva balance 80, mientras el resumen actual muestra neto -20.

### Histórico mensual del overview

El calendario del overview puede enviar `month=YYYY-MM` en sus cinco consultas GET. Por ejemplo,
`/api/financial-summary?month=2024-02` devuelve los ingresos, gastos y saldo neto del 1 al 29 de febrero.
Al quitar el mes, el resumen vuelve al corte personal actual.

| Ruta con `?month=YYYY-MM` | Qué muestra |
| --- | --- |
| `/api/financial-summary` | Resultados del mes calendario completo. Un mes vacío devuelve ceros. |
| `/api/transactions` | Movimientos del mes. El filtro se aplica antes del orden y la paginación. |
| `/api/savings-pots` | Saldo acumulado por apartado activo hasta el cierre del mes, para apartados con movimientos efectivos hasta esa fecha. |
| `/api/budgets` | Gasto de ese mes con los límites vigentes de los presupuestos. |
| `/api/recurring-payments/summary` | Resumen de pagos del mes de vencimiento. |

Las correcciones posteriores se reflejan al volver a consultar. El mes solo viaja en la petición
y los enlaces a las demás pantallas conservan sus propios filtros.

### Presupuestos mensuales

POST/PUT recibe `{"categoryId":1,"spendingLimit":100.00,"color":"#AABBCC"}`. Existe como máximo un
presupuesto activo por categoría. El color se normaliza a mayúsculas. GET de lista/detalle acepta
`month=YYYY-MM`, por defecto el mes actual. Cada límite se repite mensualmente y editarlo cambia la
definición usada también al consultar meses anteriores; no se versionan límites.

Las lecturas añaden `month`, `spent`, `available`, `limitReached` y `exceededAmount`. Solo los movimientos
negativos efectivos de esa categoría y mes consumen presupuesto; los ingresos no lo reponen.
`available = spendingLimit - spent`; `limitReached = spent >= spendingLimit`;
`exceededAmount = max(spent - spendingLimit, 0)`. Llegar exactamente a 100 de 100 activa la bandera con
exceso cero; llegar a 110 devuelve disponible -10 y exceso 10. El gasto se guarda aunque supere el límite.

POST/PUT de transacciones y el pago manual devuelven avisos `{budgetId,categoryId,month,limitReached,exceededAmount}`.
Los pagos automáticos reflejan el exceso en el siguiente GET, sin notificaciones push. Reclasificar, cambiar
fecha, borrar movimientos o esconder/restaurar apartados recalcula los importes. Cambiar el corte del
resumen financiero no cambia estos meses calendario. Borrar un presupuesto no elimina sus transacciones.

### Pagos recurrentes

POST/PUT recibe, por ejemplo:

```json
{"name":"Laptop","categoryId":1,"type":"INSTALLMENT","amount":50.00,"firstDueDate":"2027-03-05","totalInstallments":12}
```

`amount` es el importe **por cuota o vencimiento**, sin dividirlo ni multiplicarlo por el intervalo.
Una suscripción usa `type=SUBSCRIPTION`, omite `totalInstallments` y admite `intervalMonths` de 1 a 12:
1 significa cada mes, 2 cada dos meses, 3 cada tres y 12 una vez al año. Por ejemplo:

```json
{"name":"Streaming","categoryId":1,"type":"SUBSCRIPTION","amount":30.00,"firstDueDate":"2027-01-31","intervalMonths":3}
```

Ese ejemplo vence el 31 de enero, 30 de abril, 31 de julio y 31 de octubre. Si se omite `intervalMonths`,
se usa 1; las suscripciones existentes conservan su frecuencia mensual mediante V6. Los pagos
`INSTALLMENT` siguen siendo mensuales y rechazan intervalos distintos de 1. Las respuestas devuelven
`intervalMonths` para que el frontend pueda mostrar la frecuencia en cualquiera de sus idiomas.

La categoría propia activa permite generar el gasto. Cada definición tiene un calendario de vencimientos,
con ajuste al último día de meses cortos y retorno al día ancla. La primera cuota pertenece al primer mes de
`firstDueDate`. GET de lista/detalle admite `month=YYYY-MM` y expone nombre base, `billingDay`, `dueDate`,
`status`, `paidAmount`, `paidInstallments` y `totalInstallments`. El frontend forma `Laptop 3/12` con los
campos numéricos; ese sufijo no se guarda. La lista incluye definiciones activas programadas para el mes;
el detalle puede devolver `NOT_SCHEDULED` fuera del calendario. Los órdenes de fecha usan el vencimiento del mes.

Al llegar el vencimiento se registra automáticamente un gasto negativo, una vez por ocurrencia programada.
También puede marcarse anticipadamente con PUT `/{id}/payments/2027-03`, sin cuerpo. Repetirlo o competir
con el job devuelve el mismo pago sin otro gasto. El manual fecha el gasto hoy; el automático lo fecha en
el vencimiento. Un pago anticipado cuenta como pagado en su mes de vencimiento, pero afecta al presupuesto
y al corte financiero de la fecha de su gasto.

Editar nombre/categoría/fecha/importe del gasto corrige ese pago sin cambiar futuras cuotas. Borrar el
gasto o cambiarlo a positivo lo deja `VOID` (pendiente con registro automático suprimido), de modo que el
job respeta la corrección. Un nuevo marcado manual puede registrar un reemplazo. El ingreso previo puede
conservarse; volverlo negativo devuelve `409` si ya existe otro gasto activo para esa ocurrencia.
Cambiar tipo, nombre, importe, categoría o día de la definición no reescribe pagos anteriores. Después de
la primera ocurrencia no se cambia el primer mes ni se reduce el total por debajo de la mayor cuota
registrada. DELETE cancela futuros registros y conserva gastos/pagos históricos.

GET `/api/recurring-payments/summary?month=2027-03` devuelve `paid`, `pending`, `dueSoon` y `total`, cada uno
con `count` y `totalAmount`. Por vencer es un subconjunto de pendientes desde hoy hasta siete días después,
inclusive; los atrasados no entran en esa ventana. Por ejemplo, 5 pagados/155, 3 pendientes/300 y 2 por
vencer/40 dan total 8/455, sin volver a sumar los 2 por vencer. Los importes pagados reflejan correcciones
al gasto, incluso si se cancela la definición. Solo las definiciones activas generan pendientes. Estos
resúmenes son mensuales e independientes del paginado, búsqueda y preferencias de corte financiero.

El procesador interno corre cada 60 segundos y revisa como máximo 100 ocurrencias por ejecución. Recupera
atrasos tras una caída y no escribe en GET; mientras el backend esté detenido no ejecuta pagos. No realiza
cobros bancarios ni promete ejecución exacta a medianoche. Las restricciones únicas y bloqueos en PostgreSQL
evitan duplicados entre réplicas, job y pagos manuales. Un fallo revierte su ocurrencia, no impide las demás
y se reintenta luego. Configuración:

```properties
finance.business-zone=America/Mexico_City
finance.recurring-payments.scheduling-enabled=true
finance.recurring-payments.interval=PT60S
```

Se puede desactivar con `FINANCE_RECURRING_PAYMENTS_SCHEDULING_ENABLED=false`; el marcado manual sigue
disponible. Las pruebas no dedicadas lo desactivan y las de scheduling comprueban su invocación real.

### Migración y despliegue

V5 crea las seis tablas financieras y una vista de movimientos efectivos sin modificar V1–V4 ni las
cuentas existentes. Cada apartado con saldo previo no cero obtiene un único movimiento `openingBalance=true`
fechado al migrar. Conserva el saldo y el borrado del apartado; no reconstruye depósitos/retiros anteriores.
Ese movimiento participa únicamente en el corte que contiene su fecha, y los apartados borrados lo ocultan.
Los usuarios sin preferencia reciben mensual día 1 sin insertar filas de configuración.

Detén las escrituras financieras durante el despliegue; deja terminar Flyway antes de habilitar la nueva
aplicación y el job. Para revertir, desactiva el job y detén escrituras: el código anterior podría modificar
apartados sin crear movimientos. Conserva tablas y datos y reconcilia ambos antes de reactivar, o despliega
una corrección hacia delante. No borres datos ni reescribas migraciones aplicadas para hacer rollback.

## Pruebas

```bash
./mvnw verify
```

Ejecuta pruebas unitarias de validación y creación del usuario local, pruebas HTTP del registro y pruebas de
integración con un PostgreSQL independiente en Testcontainers. Las pruebas de integración requieren Docker y
cubren migración de cuentas existentes, registro, duplicados, concurrencia, rollback, autenticación, sesión,
CSRF, CORS, credenciales incorrectas y cuentas deshabilitadas. También cubren categorías, transacciones,
presupuestos, cortes personales, balance y movimientos de apartados, pagos manuales/automáticos,
recuperación tras fallos y concurrencia entre procesos. No utilizan ni borran la base de desarrollo.

## Skills

Las cuatro skills de Spring están instaladas en `.agents/skills`. Inicia Codex desde `financeApi` para que se
detecten automáticamente en el próximo turno. La fuente y revisión están registradas en `.agents/skills/SOURCES.md`.
