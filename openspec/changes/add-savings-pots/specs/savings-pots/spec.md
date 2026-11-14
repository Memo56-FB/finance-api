# Spec Delta

## Purpose

Permitir que cada usuario organice su saldo en apartados personales con metas y colores, registre abonos y retiros y pueda deshacer un borrado durante diez segundos. La contribución al saldo depende únicamente de los apartados activos; borrar un apartado no constituye un gasto.

## ADDED Requirements

### Requirement: Apartados privados y múltiples por usuario

El sistema SHALL permitir múltiples apartados por usuario, sin un máximo de negocio. SHALL determinar el dueño mediante la sesión autenticada y limitar todas las operaciones a ese usuario. Un identificador inexistente o de otro dueño SHALL devolver `404` sin revelar información del apartado.

#### Scenario: Crear varios apartados personales
- **WHEN** un usuario crea varios apartados válidos, incluidos dos con el mismo nombre
- **THEN** se crean registros independientes pertenecientes a ese usuario, con identificadores distintos

#### Scenario: Aislar los datos entre usuarios
- **WHEN** un usuario consulta, edita, abona, retira, borra o restaura un identificador de otro usuario
- **THEN** recibe `404` sin datos del apartado y no se modifica ningún registro ni saldo

#### Scenario: No aceptar al dueño desde el cliente
- **WHEN** una solicitud incluye un dueño diferente al de la sesión
- **THEN** ese dato no puede cambiar la propiedad ni permitir acceso a información de otro usuario

### Requirement: Crear un apartado con saldo inicial cero

El sistema SHALL aceptar `POST /api/savings-pots` con `name`, `targetAmount` y `color`. Un alta válida SHALL responder `201`, incluir `Location: /api/savings-pots/{id}` y devolver `id`, `name`, `targetAmount`, `balance` y `color`. El saldo inicial SHALL ser cero y SHALL persistir entre reinicios.

#### Scenario: Crear un apartado válido
- **WHEN** el dueño envía `{"name":"  Emergency fund  ","targetAmount":1500.00,"color":"#aabbcc"}` con sesión y CSRF válidos
- **THEN** recibe `201` con nombre `Emergency fund`, color `#AABBCC`, meta `1500.00`, saldo cero y el identificador generado

#### Scenario: Impedir fijar el saldo inicial desde el cliente
- **WHEN** el cliente incluye un saldo inicial distinto de cero junto con datos válidos de creación
- **THEN** ese campo no permite crear un apartado con dinero; solo los abonos posteriores pueden aumentar su saldo

### Requirement: Validar y normalizar la información del apartado

El sistema SHALL exigir nombre no blanco de 1 a 100 unidades UTF-16 tras quitar espacios exteriores, meta entre `0.01` y `99999999999999999.99` con máximo dos decimales, y color hexadecimal RGB de seis dígitos con prefijo `#`. SHALL normalizar el color a mayúsculas y rechazar datos inválidos con `400`, sin persistir cambios.

#### Scenario: Validar campos y límites
- **WHEN** se envían campos requeridos omitidos, nulos, nombre blanco o de 101 caracteres, meta no positiva, fuera de rango o con tres decimales, o un color mal formado
- **THEN** se devuelve `400` con los campos afectados y se conserva el estado previo

#### Scenario: Conservar el texto significativo del nombre
- **WHEN** se crea o edita un apartado con nombre `"  Viaje a México  "` y los demás datos válidos
- **THEN** se guarda y devuelve `Viaje a México`, preservando acentos, mayúsculas y espacios interiores

### Requirement: Consultar apartados activos y su contribución al saldo

El sistema SHALL responder a `GET /api/savings-pots` con `200` y `{ "items": [...], "totalBalance": ... }`. SHALL incluir únicamente los apartados activos del dueño, ordenados por `id` ascendente, y sumar sus saldos exactamente en `totalBalance`. Ese total SHALL representar solo apartados, sin inventar datos de presupuestos futuros.

#### Scenario: Listar apartados y sumar sus saldos
- **WHEN** el dueño tiene apartados activos con saldos `100.10` y `20.20`, uno borrado con `50.00` y otro usuario tiene `700.00`
- **THEN** recibe únicamente los dos activos propios y `totalBalance` igual a `120.30`

#### Scenario: Usuario sin apartados activos
- **WHEN** el usuario consulta sin tener apartados activos
- **THEN** recibe `200` con `items` vacío y `totalBalance` igual a cero

### Requirement: Consultar el detalle de un apartado activo

El sistema SHALL responder a `GET /api/savings-pots/{id}` con `200` y los mismos campos de un elemento del listado. Un apartado borrado SHALL devolver `404`, incluso dentro del plazo para deshacer, sin impedir que su dueño use el endpoint de restauración.

#### Scenario: Consultar un apartado propio
- **WHEN** el dueño consulta un apartado activo existente
- **THEN** recibe su identificador, nombre, meta, saldo y color, sin datos del usuario ni credenciales

#### Scenario: Ocultar un apartado borrado
- **WHEN** se consulta el detalle de un apartado borrado
- **THEN** se devuelve `404` y el apartado continúa oculto

### Requirement: Editar metadatos sin alterar el saldo

El sistema SHALL aceptar `PUT /api/savings-pots/{id}` con los tres campos `name`, `targetAmount` y `color`, aplicar las mismas validaciones del alta y responder `200` con el apartado actualizado. SHALL conservar saldo, identificador y dueño; la meta SHALL poder quedar por debajo del saldo existente.

#### Scenario: Actualizar nombre, meta y color
- **WHEN** el dueño edita un apartado con saldo `100.00` y cambia su meta a `50.00`
- **THEN** recibe los metadatos nuevos y el saldo continúa siendo `100.00`

#### Scenario: Impedir edición sobre borrados
- **WHEN** el dueño intenta editar un apartado borrado
- **THEN** recibe `404` y se conservan los metadatos y el saldo anteriores

### Requirement: Agregar dinero a un apartado

El sistema SHALL aceptar `POST /api/savings-pots/{id}/deposits` con `amount` positivo de máximo dos decimales, sumar ese importe al saldo y responder `200` con el apartado actualizado. SHALL permitir superar la meta y devolver `409` sin cambios si el saldo resultante excede `99999999999999999.99`.

#### Scenario: Aumentar el saldo y su contribución
- **WHEN** el dueño abona `25.15` a un apartado con saldo `10.10`
- **THEN** el saldo queda en `35.25` y la siguiente consulta del listado aumenta `totalBalance` en `25.15`

#### Scenario: Superar la meta
- **WHEN** un abono válido hace que el saldo supere `targetAmount` sin exceder el rango monetario
- **THEN** el abono se aplica completo y la meta no cambia

#### Scenario: Evitar desbordamiento
- **WHEN** un abono válido supera el saldo máximo admitido
- **THEN** se devuelve `409` y se conserva el saldo anterior

### Requirement: Retirar dinero sin saldo negativo

El sistema SHALL aceptar `POST /api/savings-pots/{id}/withdrawals` con `amount` positivo de máximo dos decimales, restarlo del saldo y responder `200` con el apartado actualizado. Si el importe excede el saldo disponible, SHALL responder `409` sin cambios. Retirar todo el saldo SHALL estar permitido.

#### Scenario: Retirar parte del saldo
- **WHEN** se retiran `20.20` de un saldo de `100.30`
- **THEN** el saldo queda en `80.10` y la contribución del apartado al total disminuye en `20.20`

#### Scenario: Retirar el saldo completo
- **WHEN** el importe solicitado coincide con el saldo disponible
- **THEN** se aplica el retiro y el saldo queda en cero

#### Scenario: Rechazar fondos insuficientes
- **WHEN** se intenta retirar `100.01` de un saldo de `100.00`
- **THEN** se devuelve `409`, el saldo permanece en `100.00` y el total no cambia

### Requirement: Importes exactos y entradas monetarias válidas

El sistema SHALL mantener importes decimales exactos con dos decimales como máximo. Los abonos y retiros SHALL exigir `amount` entre `0.01` y `99999999999999999.99`. Importes ausentes, nulos, negativos, cero, no numéricos, no finitos, con exceso de decimales o fuera de rango SHALL devolver `400` antes de modificar datos.

#### Scenario: No redondear una entrada inválida
- **WHEN** se intenta abonar o retirar `0.001`
- **THEN** se devuelve `400` y no se redondea ni aplica el importe

#### Scenario: Mantener precisión decimal
- **WHEN** un apartado vacío recibe abonos de `0.10` y `0.20`
- **THEN** su saldo es exactamente `0.30`, sin residuos de punto flotante

### Requirement: Conservar el saldo ante operaciones concurrentes

El sistema SHALL aplicar cada mutación exitosa una vez por solicitud y conservar todos los cambios confirmados ante concurrencia. SHALL impedir saldo negativo, abonos o retiros sobre apartados borrados, y restauraciones duplicadas que multipliquen el saldo.

#### Scenario: Abonos simultáneos
- **WHEN** dos solicitudes concurrentes abonan `10.00` y `20.00` a un saldo inicial de cero y ambas terminan correctamente
- **THEN** el saldo final es `30.00`

#### Scenario: Retiros simultáneos sin fondos para ambos
- **WHEN** dos solicitudes concurrentes intentan retirar `80.00` de un saldo inicial de `100.00`
- **THEN** una recibe `200`, la otra `409` y el saldo final es `20.00`

#### Scenario: Borrado concurrente con un abono
- **WHEN** un abono y un borrado compiten sobre el mismo apartado
- **THEN** si el abono se aplica primero su importe queda conservado en el registro borrado, y si el borrado se aplica primero el abono recibe `404` sin modificarlo

### Requirement: Borrado lógico con exclusión inmediata del saldo

El sistema SHALL aceptar `DELETE /api/savings-pots/{id}` incluso con saldo positivo y responder `200` con `id`, `deletedAt` y `restoreUntil`. SHALL conservar el registro, metadatos y saldo; ocultarlo y excluirlo del total desde que se confirma el borrado. SHALL fijar `restoreUntil` diez segundos después de `deletedAt` y no registrar un gasto ni un retiro.

#### Scenario: Borrar un apartado con dinero
- **WHEN** se borra un apartado con saldo `150.00`
- **THEN** el saldo conservado sigue siendo `150.00`, el apartado desaparece del listado y el total disminuye en `150.00` sin generar un gasto

#### Scenario: No prolongar el plazo mediante borrados repetidos
- **WHEN** el dueño repite `DELETE` sobre un apartado que sigue borrado
- **THEN** recibe los mismos `deletedAt` y `restoreUntil`, sin reiniciar el plazo ni modificar el saldo

### Requirement: Deshacer el borrado durante diez segundos

El sistema SHALL aceptar `POST /api/savings-pots/{id}/restore` sin cuerpo y responder `200` con el apartado restaurado solo si la hora del backend al comprobar la operación es anterior a `restoreUntil`. SHALL conservar identificador, metadatos y saldo y volver a incluirlo en el total. Si el plazo venció, SHALL responder `409` sin cambios.

#### Scenario: Restaurar dentro del plazo
- **WHEN** el dueño restaura un apartado 9 segundos después de borrarlo
- **THEN** recupera exactamente el saldo y los metadatos previos y su saldo vuelve a contribuir al total

#### Scenario: Rechazar en el límite o después
- **WHEN** la hora de comprobación coincide con `restoreUntil` o es posterior
- **THEN** se devuelve `409` y el apartado permanece borrado y excluido del total

#### Scenario: Repetir restauración sobre un apartado activo
- **WHEN** el dueño envía una restauración para un apartado que ya está activo
- **THEN** recibe `200` con su estado actual sin duplicar el saldo ni la contribución al total

#### Scenario: Conservar el plazo entre reinicios
- **WHEN** la aplicación reinicia después del borrado y se solicita restaurar
- **THEN** el plazo se calcula desde la fecha de borrado original y no se reinicia

#### Scenario: No permitir mutaciones monetarias durante el borrado
- **WHEN** se intenta abonar o retirar en un apartado borrado, incluso antes de vencer el plazo
- **THEN** se devuelve `404` y se conserva el saldo que podría restaurarse

### Requirement: Autenticación y protección CSRF existentes

El sistema SHALL exigir sesión autenticada para todos los endpoints de apartados y CSRF válido para `POST`, `PUT` y `DELETE`. SHALL conservar las reglas actuales de cookies y permitir solicitudes desde el origen frontend configurado, incluyendo preflight de `PUT` y `DELETE`, sin ampliar a orígenes arbitrarios.

#### Scenario: Consultar sin autenticación
- **WHEN** un cliente sin sesión consulta la lista o el detalle
- **THEN** recibe `401` sin redirección a una página de login

#### Scenario: Escribir sin CSRF válido
- **WHEN** un usuario autenticado crea, edita, abona, retira, borra o restaura sin CSRF o con un token inválido
- **THEN** recibe `403` y no se modifica ningún registro ni saldo

#### Scenario: Escribir sin sesión con CSRF válido
- **WHEN** un cliente anónimo intenta una mutación con un token CSRF válido
- **THEN** recibe `401` y no se modifica ningún dato

#### Scenario: Mantener el origen permitido
- **WHEN** se realiza un preflight para `PUT` o `DELETE` desde el origen configurado y desde uno no permitido
- **THEN** el primero permite el método con credenciales y el segundo se rechaza

### Requirement: Errores seguros y consistentes

El sistema SHALL devolver `application/problem+json` con `status`, `title` y `detail` para errores de aplicación `400`, `404` y `409`. Los errores de campos SHALL incluir `errors` con `field` y `message`. SHALL excluir valores rechazados, credenciales, datos ajenos y detalles de SQL o excepciones internas. Los identificadores mal formados o no positivos SHALL causar `400`.

#### Scenario: Rechazar un cuerpo ilegible
- **WHEN** una operación que requiere JSON recibe cuerpo vacío, `null`, un arreglo o JSON mal formado con sesión y CSRF válidos
- **THEN** recibe `400 application/problem+json` sin repetir el cuerpo y no se modifica el estado

#### Scenario: Informar un conflicto de saldo o plazo
- **WHEN** una operación no puede completarse por saldo insuficiente, desbordamiento o plazo de restauración vencido
- **THEN** recibe `409 application/problem+json` con un detalle controlado y sin información técnica interna
