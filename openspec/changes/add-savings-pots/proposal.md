# Proposal

## Why

El usuario necesita organizar su saldo en apartados personales con nombre, meta y color, y poder agregar o retirar dinero de cada uno. La API solo tiene autenticación; se requiere esta primera funcionalidad financiera sin depender de presupuestos, categorías ni transacciones que todavía no existen.

## What Changes

- Crear `savings_pots`, vinculada al usuario existente, para almacenar nombre, meta, color, saldo y fecha de borrado lógico. Cada usuario podrá tener múltiples apartados sin un máximo de negocio y sin compartirlos con otros usuarios.
- Agregar endpoints autenticados para listar, consultar, crear y editar apartados, agregar y retirar dinero, borrarlos y deshacer el borrado.
- Iniciar cada apartado en cero; registrar abonos y retiros con aritmética decimal exacta y evitar saldos negativos, desbordamientos y actualizaciones perdidas ante concurrencia.
- Calcular la contribución de los apartados al saldo del usuario sumando únicamente los apartados activos. Los abonos aumentan esa contribución y los retiros la reducen. Este cambio no crea un saldo global persistido ni descuenta fondos de una cuenta externa.
- Permitir borrar apartados con dinero: desaparecen inmediatamente de las consultas y de la suma, conservando los datos para deshacer la acción durante **10 segundos**. Borrar no genera un gasto; restaurar vuelve a incluir exactamente el saldo conservado.
- Proteger las escrituras con el CSRF existente y limitar toda consulta o mutación al dueño autenticado.
- Documentar y probar el contrato, el aislamiento entre usuarios, los importes, la concurrencia y el plazo de restauración.

Fuera de alcance: frontend, cuentas bancarias o movimientos de dinero real, tablas de presupuestos/categorías/transacciones, cálculo del saldo global que incluya presupuestos, historial de abonos/retiros, límites de cantidad de apartados, filtros y paginado, purga física programada y commits. La integración con presupuestos será un cambio posterior; el saldo devuelto aquí corresponde solo a los apartados activos.

## Capabilities

### New Capabilities

- `savings-pots`: administración de apartados personales, metas y colores, abonos/retiros seguros, contribución al saldo y borrado reversible durante 10 segundos.

### Modified Capabilities

Ninguna. `openspec list --specs` no contiene capacidades publicadas; los endpoints de autenticación conservan su contrato.

## Impact

- Nueva funcionalidad `dev.memofb.finance.savings`, usando Spring MVC, Bean Validation, JDBC y PostgreSQL ya instalados; identificadores de código, tablas, columnas y JSON en inglés.
- Nueva migración Flyway `V4__create_savings_pots.sql`, sin modificar V1–V3 ni las cuentas existentes.
- API bajo `/api/savings-pots`; ampliar los métodos CORS permitidos a `PUT` y `DELETE`, conservando el origen explícito, las cookies y CSRF.
- Pruebas de validación/HTTP y de PostgreSQL con Testcontainers, ejecutadas mediante `./mvnw verify`; actualización del README durante la implementación.
- El cambio anterior `add-user-registration` aún menciona autoridades, pero el código y V3 ya las eliminaron. Esta propuesta parte de la implementación actual y no reintroduce roles ni modifica esos artefactos anteriores.
