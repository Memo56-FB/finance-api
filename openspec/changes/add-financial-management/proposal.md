# Proposal

## Why

La aplicación ya administra apartados, pero aún no registra transacciones generales, presupuestos, categorías personales ni pagos recurrentes. El usuario necesita completar el backend y consultar saldo, ingresos y gastos calculados automáticamente para su corte quincenal o mensual, sin duplicar los importes procedentes de apartados o pagos.

## What Changes

- Crear categorías privadas por usuario, sin un máximo de negocio, y sus operaciones de consulta, creación, edición y eliminación.
- Crear un registro de transacciones con nombre, categoría, fecha e importe positivo o negativo; ofrecer GET/POST/PUT/DELETE, búsqueda por nombre, paginado y seis órdenes independientes de la búsqueda.
- Exponer un resumen con `currentBalance`, `income` y `expenses`, calculado únicamente desde las transacciones vigentes del periodo de corte actual. `currentBalance` será el neto de ese periodo, sin arrastrar importes de cortes anteriores; los tres valores serán cero cuando el periodo no tenga movimientos. No habrá PUT ni DELETE de esos totales y se permiten valores negativos de saldo.
- Guardar una preferencia por usuario con `frequency` (`MONTHLY` o `SEMIMONTHLY`) y `startDay` (1–31), consultable y editable mediante GET/PUT. El valor por defecto será mensual desde el día 1. Los cortes siguen el calendario: mensual desde el día elegido hasta el anterior al corte siguiente; quincenal divide ese ciclo en un primer tramo de quince días y un segundo hasta el próximo corte. Si el día no existe en un mes, se usa su último día sin perder el ancla original.
- Devolver `periodStart` y `periodEnd` inclusivos en el resumen. Cambiar la preferencia recalcula la consulta sin modificar transacciones, reiniciar saldos de apartados ni cambiar presupuestos o vencimientos recurrentes, que siguen siendo mensuales.
- Registrar automáticamente abonos y retiros de apartados como transacciones con `potId` y procedencia estable, permitiendo al frontend traducir sus etiquetas. Conservar los saldos existentes y la regla de que borrar un apartado retira su contribución sin generar un gasto; restaurarlo dentro de diez segundos la recupera.
- Crear presupuestos mensuales por categoría, con máximo de gasto y color. Calcular `spent`, `available`, `limitReached` y `exceededAmount` desde los gastos del mes y permitir superar el límite, devolviendo avisos sin rechazar el movimiento.
- Crear pagos mensuales de tipo `SUBSCRIPTION` o `INSTALLMENT`, con nombre, categoría, vencimiento e importe por cuota. Para pagos a meses, exponer progreso pagado/total sin concatenar textos traducidos en la base.
- Registrar cada vencimiento como pago realizado y transacción de gasto, automáticamente o mediante marcado manual, una sola vez por periodo. Proporcionar búsqueda, seis órdenes y resumen mensual de realizados, pendientes y por vencer en siete días, con cantidad e importe.
- Mantener sesión, CSRF, aislamiento por usuario y Swagger local; cubrir migraciones, cálculos, automatización, edición/eliminación de movimientos y concurrencia con PostgreSQL real.

Fuera de alcance: frontend, cobros bancarios reales, conversión de monedas, avisos push, cambios de autenticación, purga física de históricos y commits. Los importes son registros de la aplicación. No se introducen brokers ni servicios externos para la automatización.

## Capabilities

### New Capabilities

- `categories`: catálogo personal de categorías y conservación de referencias históricas.
- `transactions`: movimientos editables con importe firmado, consultas ordenadas e integración con apartados y pagos recurrentes.
- `financial-summary`: preferencias de corte personales, calendario quincenal/mensual y saldo neto, ingresos y gastos del periodo sin contarlos más de una vez.
- `budgets`: límites mensuales por categoría, disponible y avisos de exceso no bloqueantes.
- `recurring-payments`: suscripciones y pagos a meses, vencimientos, marcado manual/automático, progreso y resúmenes mensuales.

### Modified Capabilities

Ninguna capacidad publicada: `openspec list --specs` está vacío. La integración modifica la persistencia interna de `savings` y se especifica en la nueva capacidad `transactions`, conservando sus ocho rutas y respuestas existentes. No se reescriben los artefactos del cambio anterior `add-savings-pots`.

## Impact

- Nuevas tablas `categories`, `transactions`, `budgets`, `recurring_payments`, `recurring_payment_occurrences` y `financial_summary_preferences`, mediante migraciones posteriores a V4. La última guarda solo la configuración de corte; los totales, periodos concretos y banderas derivados no se persisten.
- Funcionalidades hermanas de `auth` y `savings`: categorías, transacciones, resumen, presupuestos y pagos recurrentes, con JDBC, records y transacciones de servicio.
- Integración atómica del saldo de apartados con sus movimientos, backfill de saldos preexistentes y una tarea programada de Spring para vencimientos, protegida por claves únicas y bloqueos en PostgreSQL.
- Documentación y pruebas de los endpoints nuevos, regresiones de apartados/autenticación y ejecución de `./mvnw verify` con Docker. Identificadores de código, tablas, columnas, enums y JSON en inglés.
