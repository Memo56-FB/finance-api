# Tasks

Las tareas corresponden a [design.md](design.md) y las cinco capacidades de `specs/`. Los contratos nuevos están en inglés y se conservan los de autenticación y apartados. Ninguna tarea está implementada por la creación de esta propuesta.

## 1. Persistencia, calendario y coordinación

- [ ] 1.1 Crear V5 con las seis tablas, incluida la preferencia de corte única por usuario, pertenencia compuesta, restricciones de importe/origen/tipo y de frecuencia/día, e índices únicos de presupuesto y ocurrencia; verificar con PostgreSQL real una instalación vacía y el rechazo de referencias entre usuarios, preferencias duplicadas/inválidas y gastos duplicados por ocurrencia.
- [ ] 1.2 Añadir el backfill de apartados con balance no cero como movimientos `POT` de apertura, sin alterar sus saldos ni marcas de borrado; verificar una actualización desde V4 con apartados activos, borrados y vacíos y una segunda ejecución de Flyway sin duplicados.
- [ ] 1.3 Incorporar el bloqueo de propietario compartido y el calendario de negocio reutilizando `Clock`, con zona explícita; verificar mediante pruebas que dos propietarios no se bloquean entre sí y que hoy/mes no dependen de la zona del sistema.
- [ ] 1.4 Ajustar las limpiezas y fixtures afectados por las nuevas referencias y documentar en README el backfill, la zona y el despliegue con escrituras detenidas; verificar las regresiones de migración/repositorios/apartados, el defecto mensual día 1 para usuarios existentes sin preferencias y que no se requiere modificar V1–V4.

## 2. Categorías privadas

- [ ] 2.1 Implementar records de entrada/salida y CRUD JDBC de categorías por propietario con nombres normalizados; verificar límites de nombre, catálogo vacío, múltiples categorías y aislamiento en pruebas de repositorio y validación.
- [ ] 2.2 Implementar borrado lógico, conservación de referencias históricas y conflicto ante presupuestos o definiciones recurrentes activos; verificar que borrar una categoría no altera ninguna transacción ni saldo y que no permite nuevas asignaciones.
- [ ] 2.3 Exponer los endpoints de categorías con sesión, CSRF y errores `ProblemDetail`; verificar respuestas 201/200/204, validación 400, extranjero 404 y mutación sin CSRF 403 mediante pruebas MVC.
- [ ] 2.4 Documentar en README cuerpos, rutas y regla de borrado de categorías; verificar los ejemplos contra el contrato y la presencia de las operaciones en OpenAPI local.

## 3. Transacciones manuales y resumen por corte personal

- [ ] 3.1 Implementar lectura y upsert de la preferencia por propietario con DTO `{frequency, startDay}`, defecto `MONTHLY`/1 sin escritura en GET y reemplazo completo en PUT; verificar persistencia, aislamiento, campos obligatorios, frecuencias admitidas y días 1–31 en pruebas de validación y PostgreSQL.
- [ ] 3.2 Implementar resolución del corte actual con `Clock` y zona de negocio, anclas mensuales recortadas y dos tramos quincenales; verificar mensual día 4, quincenal día 1 y día 4, días 29–31, año bisiesto, cambio de año y límites contiguos sin huecos, solapamientos ni tercer tramo.
- [ ] 3.3 Implementar solicitudes y respuestas de transacciones con dinero firmado exacto, fecha ISO y categoría propia, sin aceptar metadatos de origen; verificar con pruebas importes positivos/negativos, cero, exceso de decimales, extremos, nombres inválidos y categorías ajenas/inactivas.
- [ ] 3.4 Implementar CRUD de transacciones manuales y condición compartida de efectividad, bajo bloqueo de propietario; verificar correcciones de los cuatro campos, borrado lógico e inexistencia de cambios parciales ante errores.
- [ ] 3.5 Implementar búsqueda literal por nombre, los seis órdenes independientes y paginación acotada con conteo consistente; verificar con PostgreSQL combinaciones con/sin búsqueda, comodines literales, empates, importes con signo, página vacía y parámetros inválidos.
- [ ] 3.6 Implementar GET `/api/financial-summary` con fechas inclusivas y agregados del corte, sin arrastre ni contadores; verificar saldo negativo, ceros en un corte vacío, inclusión exacta de extremos, exclusión de fechas externas, cambio de corte y correcciones de fecha/importe, también con más movimientos que una página y ante actualización concurrente de preferencias.
- [ ] 3.7 Exponer transacciones, resumen y GET/PUT de preferencias con los mismos controles de seguridad; verificar sesión, CSRF, aislamiento y 404 de recursos ajenos, preferencias inválidas sin cambios, ausencia de PUT/DELETE de totales y ningún cambio en movimientos/apartados/presupuestos/pagos al guardar preferencias.
- [ ] 3.8 Documentar en README cuerpos, consultas, órdenes, GET/PUT de preferencias y fechas del resumen, indicando que currentBalance es el neto del corte y que la segunda quincena se ajusta al calendario; verificar ejemplos y que Swagger descubre los nuevos endpoints y schemas.

## 4. Movimientos automáticos de apartados

- [ ] 4.1 Adaptar depósitos y retiros de `SavingsPotService` para bloquear primero al propietario y guardar movimiento y balance atómicamente; verificar depósito de 100 y retiro de 20 en el mismo corte con resumen 80 y dos movimientos, y en cortes distintos con apartado 80/resumen actual -20, además de rollback completo ante fallo de persistencia.
- [ ] 4.2 Permitir editar y borrar movimientos de apartados ajustando el balance por diferencia o inversión, manteniendo el origen inmutable; verificar cambios de nombre/categoría/fecha/importe, límites del apartado y conflictos 409 sin cambios parciales.
- [ ] 4.3 Integrar la visibilidad de movimientos con borrado/restauración de apartados; verificar exclusión de lista y resumen sin gasto compensatorio, restauración sin duplicados, 404 mientras están ocultos y expiración exacta después de adquirir los bloqueos.
- [ ] 4.4 Adaptar fixtures y pruebas de concurrencia de apartados al nuevo orden de bloqueos y documentar en README origen, apertura y diferencia entre balance del apartado y neto del corte; verificar las ocho rutas/respuestas existentes, conservación del balance al cambiar de periodo y concurrencia de depósito, corrección, borrado y restauración.

## 5. Presupuestos mensuales y avisos

- [ ] 5.1 Implementar CRUD privado de presupuestos con categoría activa, límite positivo, color normalizado y unicidad activa por categoría; verificar con PostgreSQL altas, cambios, borrado y conflicto de duplicados o referencias ajenas.
- [ ] 5.2 Implementar agregados mensuales de gastos efectivos y campos derivados `spent`, `available`, `limitReached`, `exceededAmount`; verificar meses contiguos, año bisiesto, ingresos que no reponen presupuesto e igualdad/exceso del límite.
- [ ] 5.3 Añadir `budgetWarnings` a POST/PUT de transacciones, calculados después de la escritura y para categorías/meses afectados; verificar que superar el límite permite la operación y devuelve la cantidad exacta, y que reclasificar, borrar o esconder/restaurar un apartado corrige los cálculos.
- [ ] 5.4 Exponer endpoints mensuales con validación de `month`, seguridad y `ProblemDetail`; verificar CSRF, propiedad, datos inválidos, que borrar un presupuesto conserva movimientos/resumen y que cambiar el corte del resumen no altera cálculos mensuales de presupuestos.
- [ ] 5.5 Documentar en README meses, reglas de cálculo, límites repetidos y formato de avisos; verificar los ejemplos de disponible negativo e igualdad y que Swagger muestra los campos derivados y las respuestas con avisos.

## 6. Definiciones y pagos recurrentes manuales

- [ ] 6.1 Implementar los dos tipos de definición y la función mensual de vencimiento/secuencia; verificar validación de cuotas/importe, día 31 en febrero y retorno al ancla en marzo, pagos antes del primer mes y periodos posteriores a la última cuota.
- [ ] 6.2 Implementar CRUD privado y cambios de configuración sin reescribir ocurrencias existentes; verificar categoría propia, primer mes inmutable tras actividad, reducción inválida de cuotas, cambio de día/importe y cancelación conservando gastos históricos.
- [ ] 6.3 Implementar PUT de marcado mensual pagado en una transacción con ocurrencia, gasto negativo, origen y avisos de presupuesto; verificar pago anticipado, repetición con el mismo resultado, saldo negativo permitido y rollback ante fallo entre escrituras.
- [ ] 6.4 Integrar PUT/DELETE de transacciones recurrentes con el estado de la ocurrencia; verificar corrección del gasto, cambio a ingreso o borrado como `VOID`, pago manual de reemplazo, edición posterior del ingreso original y conflicto si se intenta crear dos gastos activos.
- [ ] 6.5 Exponer las operaciones de definición y marcado con sesión/CSRF, DTOs y errores coherentes; verificar con MVC periodos inválidos, ID ajeno 404, conflictos 409 y metadatos de origen no manipulables.
- [ ] 6.6 Documentar en README cuerpos, tipo, importe por cuota, progreso independiente del nombre y semántica de correcciones; verificar ejemplos de `3/12`, pagos anticipados y cancelación contra pruebas y OpenAPI local.

## 7. Consultas y resúmenes de pagos

- [ ] 7.1 Implementar la lista mensual paginada con búsqueda de nombre y los seis órdenes; verificar en PostgreSQL independencia de filtros/orden, vencimientos recortados, importes numéricos, desempates, parámetros inválidos y ausencia de series fuera de su calendario.
- [ ] 7.2 Implementar detalle de progreso real y GET de resumen `paid`, `pending`, `dueSoon` y `total`, cada uno con count/totalAmount; verificar el ejemplo 5/155, 3/300 y 2/40, sin sumar dos veces por vencer ni depender de la página o búsqueda.
- [ ] 7.3 Cubrir meses, hoy y extremos de siete días con reloj controlado, pagos anticipados, correcciones `VOID`, importes pagados editados y definiciones canceladas; verificar históricos pagados y pendientes solo de definiciones activas, y que preferencias quincenales no alteran ese resumen mensual ni sus vencimientos.
- [ ] 7.4 Documentar en README filtros, significado de cada total y diferencia entre mes de vencimiento y fecha real del gasto; verificar ejemplos y que los datos de progreso permiten traducir la UI sin sufijos persistidos.

## 8. Registro automático al vencimiento

- [ ] 8.1 Habilitar scheduling en configuración dedicada y procesar candidatos por páginas con lotes acotados, usando el servicio transaccional de pago y `next_auto_sequence`; verificar vencimiento, ninguna escritura en GET, recuperación de varios meses sin incorporar gastos fuera del corte al resumen actual y fin de cuotas.
- [ ] 8.2 Respetar estados `PAID`/`VOID`, no adelantar el cursor por un pago manual futuro y aislar fallos por ocurrencia; verificar reintentos tras rollback, avance persistido del cursor y que una serie fallida no impide procesar las demás.
- [ ] 8.3 Probar concurrencia real en PostgreSQL entre dos procesadores, pago manual, edición de transacción y cancelación; verificar un solo gasto activo por periodo, totales exactos y ausencia de regeneración de correcciones del usuario.
- [ ] 8.4 Añadir la propiedad para desactivar el job y actualizar `AuthFlowTest`, `SavingsPotServiceTest`, `LocalSwaggerTest` y nuevos tests no dedicados para evitar ejecuciones por reloj; verificar las regresiones de esas suites y una prueba que ejercite el scheduling habilitado.
- [ ] 8.5 Documentar en README intervalo, zona de negocio, recuperación tras caídas y apagado operativo; verificar que los ejemplos de configuración coinciden con las propiedades reales y no prometen cobros bancarios ni ejecución exacta a medianoche.

## 9. Verificación integrada

- [ ] 9.1 Ejecutar un flujo integrado de registro/login/CSRF, preferencias distintas por usuario, transacciones, apartados, presupuesto y pago recurrente; verificar con PostgreSQL límites del corte, netos sin arrastre/duplicados, cambio inmediato de preferencias sin reiniciar datos, presupuestos/pagos mensuales intactos, aislamiento y Swagger solo en perfil local.
- [ ] 9.2 Ejecutar `./mvnw verify` con Docker y revisar el resultado completo; verificar que pasan las pruebas de migración desde V4, unitarias, integración y concurrencia, sin cambios de dependencias o autenticación ajenos al alcance.
- [ ] 9.3 Revisar el diff y la coherencia con propuesta/especificaciones y ejecutar `openspec validate add-financial-management --strict`; verificar que no se modificaron V1–V4, no se incluyeron secretos y no se hicieron commits antes de la revisión del usuario.
