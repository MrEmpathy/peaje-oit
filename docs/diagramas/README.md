# Diagramas de casos de uso — Peaje IoT

Cada diagrama está en tres formatos: `.mmd` (fuente Mermaid, editable), `.svg` y `.png`.

**Leyenda:** óvalo azul = caso de uso implementado en la app · óvalo gris con borde punteado = disponible solo en la API REST
(no tiene pantalla en la app) · verde = barrera abierta · rojo = flujo alternativo o de rechazo ·
flecha punteada = relación «include» / «extend».

## Diagrama general del sistema

Actores: conductor y ESP32 de la garita. Resume todos los casos de uso del sistema.

![Diagrama general del sistema](01_general.png)

<details><summary>Código Mermaid</summary>

```mermaid
%% Diagrama de casos de uso general — Peaje IoT
flowchart LR
    conductor["👤<br/>Conductor<br/>(usuario de la app)"]
    lector["📟<br/>ESP32 de la garita<br/>(lector RFID/NFC + barrera)"]

    subgraph sistema["Sistema Peaje IoT"]
        direction TB
        uc1(["Registrarse"])
        uc2(["Iniciar sesión"])
        uc3(["Consultar saldo y vehículo"])
        uc4(["Recargar saldo"])
        uc5(["Pagar peaje con el celular (NFC)"])
        uc6(["Pagar peaje con tarjeta RFID"])
        uc7(["Cobrar tarifa según garita y tipo de vehículo"])
        uc8(["Abrir o denegar la barrera"])
        uc9(["Consultar historial de pasos y recargas<br/>(solo API)"])
        uc10(["Gestionar tarjetas RFID<br/>(solo API)"])
        uc11(["Actualizar vehículo<br/>(solo API)"])
        uc12(["Cerrar sesión"])
    end

    conductor --- uc1
    conductor --- uc2
    conductor --- uc3
    conductor --- uc4
    conductor --- uc5
    conductor --- uc6
    conductor --- uc9
    conductor --- uc10
    conductor --- uc11
    conductor --- uc12

    uc5 --- lector
    uc6 --- lector
    uc8 --- lector

    uc5 -.->|«include»| uc7
    uc6 -.->|«include»| uc7
    uc7 -.->|«include»| uc8

    classDef actor fill:#fff,stroke:#333,stroke-width:1px,color:#111
    classDef caso fill:#e8f1fb,stroke:#2b6cb0,color:#111
    classDef api fill:#f1f1f1,stroke:#888,stroke-dasharray:5 4,color:#333
    class conductor,lector actor
    class uc1,uc2,uc3,uc4,uc5,uc6,uc7,uc8,uc12 caso
    class uc9,uc10,uc11 api
    style sistema fill:#fafafa,stroke:#555,stroke-width:2px
```

</details>

## Login y autenticación (sección 3.1)

Registro de cuenta e inicio de sesión con validación de campos, verificación bcrypt y token JWT; bloqueo tras 3 intentos fallidos, manejo de errores y expiración de la sesión.

![Login y autenticación (sección 3.1)](02_autenticacion.png)

<details><summary>Código Mermaid</summary>

```mermaid
%% Casos de uso — Login y autenticación (sección 3.1)
flowchart LR
    conductor["👤<br/>Conductor"]
    api["🖥️<br/>API REST<br/>(FastAPI + MySQL)"]

    subgraph app["Sistema Peaje IoT (app Android + API REST)"]
        direction TB
        reg(["Registrarse<br/>(nombre, correo, contraseña, patente, tipo de vehículo)"])
        login(["Iniciar sesión"])
        validar(["Validar campos<br/>(vacíos, formato de correo)"])
        validarReg(["Validar datos del registro<br/>(contraseña ≥ 8 y repetida, patente)"])
        tipos(["Elegir tipo de vehículo<br/>(GET /tipos-vehiculo)"])
        bloqueo(["Bloquear 60 s tras<br/>3 intentos fallidos"])
        verificar(["Verificar credenciales<br/>(hash bcrypt, cuenta activa)"])
        token(["Recibir y guardar token JWT"])
        error(["Mostrar mensaje de error<br/>(credenciales, cuenta inactiva,<br/>demasiados intentos, sin conexión)"])
        mantener(["Mantener la sesión al reabrir la app"])
        expira(["Cerrar sesión por token expirado (401)"])
        logout(["Cerrar sesión"])
        confirmar(["Confirmar cierre de sesión"])
    end

    conductor --- reg
    conductor --- login
    conductor --- mantener
    conductor --- logout

    reg -.->|«include»| validarReg
    reg -.->|«include»| tipos
    reg -.->|«include»| token
    login -.->|«include»| validar
    login -.->|«include»| verificar
    login -.->|«include»| token
    login <-.-|«extend»| error
    mantener <-.-|«extend»| expira
    login <-.-|«extend»<br/>3 fallos| bloqueo
    logout -.->|«include»| confirmar

    reg --- api
    verificar --- api

    classDef actor fill:#fff,stroke:#333,color:#111
    classDef caso fill:#e8f1fb,stroke:#2b6cb0,color:#111
    classDef api fill:#f1f1f1,stroke:#888,stroke-dasharray:5 4,color:#333
    classDef alt fill:#fdecea,stroke:#c53030,color:#111
    class conductor,api actor
    class reg,validarReg,tipos,login,validar,verificar,token,mantener,logout,confirmar caso
    class error,expira,bloqueo alt
    style app fill:#fafafa,stroke:#555,stroke-width:2px
```

</details>

## Pago en la barrera y conexión IoT (sección 3.2)

Pago por NFC o RFID, cobro según tipo de vehículo en el worker MQTT, apertura o rechazo de la barrera.

![Pago en la barrera y conexión IoT (sección 3.2)](03_pago_barrera.png)

<details><summary>Código Mermaid</summary>

```mermaid
%% Casos de uso — Pago en la barrera y conexión con el IoT (sección 3.2)
flowchart LR
    conductor["👤<br/>Conductor"]
    esp["📟<br/>ESP32 de la garita<br/>(lector, servo, LCD, HC-SR04)"]
    worker["⚙️<br/>Worker de cobro<br/>(MQTT + MySQL)"]

    subgraph sistema["Sistema Peaje IoT — barrera"]
        direction TB
        nfc(["Pagar con el celular (NFC)"])
        rfid(["Pagar con tarjeta RFID"])
        resultado(["Ver resultado del pago<br/>y saldo nuevo en la app"])
        tokenNfc(["Obtener token de un solo uso<br/>(vigente 5 min, renovación automática)"])
        cobro(["Procesar cobro del peaje<br/>(lectura publicada por MQTT)"])
        validarC(["Validar garita, credencial y usuario"])
        tarifa(["Calcular tarifa por tipo de vehículo"])
        descontar(["Descontar saldo en transacción atómica"])
        registrar(["Registrar transacción"])
        abrir(["Abrir barrera<br/>(orden ABRIR + LCD)"])
        denegar(["Denegar paso (orden DENEGAR + motivo en LCD)<br/>saldo insuficiente · token reutilizado ·<br/>tarifa no definida · credencial inactiva"])
        cerrar(["Detectar paso del vehículo<br/>y cerrar barrera"])
    end

    conductor --- nfc
    conductor --- rfid
    conductor --- resultado

    nfc -.->|«include»| tokenNfc
    nfc -.->|«include»| cobro
    rfid -.->|«include»| cobro
    cobro -.->|«include»| validarC
    cobro -.->|«include»| tarifa
    cobro -.->|«include»| descontar
    cobro -.->|«include»| registrar
    cobro <-.-|«extend»<br/>aprobado| abrir
    cobro <-.-|«extend»<br/>rechazado| denegar
    abrir -.->|«include»| cerrar

    nfc --- esp
    rfid --- esp
    abrir --- esp
    denegar --- esp
    cerrar --- esp
    cobro --- worker

    classDef actor fill:#fff,stroke:#333,color:#111
    classDef caso fill:#e8f1fb,stroke:#2b6cb0,color:#111
    classDef ok fill:#e6f6ec,stroke:#2f855a,color:#111
    classDef alt fill:#fdecea,stroke:#c53030,color:#111
    class conductor,esp,worker actor
    class nfc,rfid,resultado,tokenNfc,cobro,validarC,tarifa,descontar,registrar,cerrar caso
    class abrir ok
    class denegar alt
    style sistema fill:#fafafa,stroke:#555,stroke-width:2px
```

</details>

## Cuenta, saldo y consulta de datos

Consulta de saldo, recargas y datos de la cuenta, junto con las funciones disponibles en la API.

![Cuenta, saldo y consulta de datos](04_cuenta_saldo.png)

<details><summary>Código Mermaid</summary>

```mermaid
%% Casos de uso — Cuenta, saldo y consulta de datos
flowchart LR
    conductor["👤<br/>Conductor"]
    api["🖥️<br/>API REST<br/>(FastAPI + MySQL)"]

    subgraph app["Sistema Peaje IoT (app Android + API REST)"]
        direction TB
        saldo(["Consultar saldo y vehículo<br/>(Inicio, deslizar para actualizar)"])
        recargar(["Recargar saldo"])
        monto(["Validar monto<br/>(mínimo, máximo)"])
        rapido(["Elegir monto rápido<br/>($2.000 a $20.000)"])
        historial(["Consultar historial de pasos<br/>(solo API)"])
        recargas(["Consultar historial de recargas<br/>(solo API)"])
        tarifas(["Consultar tarifas por garita y vehículo<br/>(solo API)"])
        cuenta(["Ver datos de la cuenta<br/>(nombre, correo, patente, UID NFC)"])
        vehiculo(["Actualizar vehículo — solo API<br/>(patente y tipo)"])
        tarjetas(["Gestionar tarjetas RFID — solo API<br/>(agregar, listar, eliminar)"])
    end

    conductor --- saldo
    conductor --- recargar
    conductor --- historial
    conductor --- recargas
    conductor --- tarifas
    conductor --- cuenta
    conductor --- vehiculo
    conductor --- tarjetas

    recargar -.->|«include»| monto
    recargar <-.-|«extend»| rapido

    saldo --- api
    recargar --- api
    historial --- api
    recargas --- api
    tarifas --- api
    vehiculo --- api
    tarjetas --- api

    classDef actor fill:#fff,stroke:#333,color:#111
    classDef caso fill:#e8f1fb,stroke:#2b6cb0,color:#111
    classDef api fill:#f1f1f1,stroke:#888,stroke-dasharray:5 4,color:#333
    class conductor,api actor
    class saldo,recargar,monto,rapido,cuenta caso
    class historial,recargas,tarifas,vehiculo,tarjetas api
    style app fill:#fafafa,stroke:#555,stroke-width:2px
```

</details>

## Administrador (panel web)

Panel web en `/panel` para cuentas con rol ADMIN: resumen, pasos, usuarios (ajuste de saldo, activar o desactivar cuentas, bloquear tarjetas), garitas y tarifas, apertura manual de la barrera y auditoría. El rol se otorga solo desde la consola del servidor.

![Administrador (panel web)](05_administrador.png)

<details><summary>Código Mermaid</summary>

```mermaid
%% Casos de uso — Administrador (panel web)
flowchart LR
    admin["🧑‍💼<br/>Administrador"]
    esp["📟<br/>ESP32 de la garita"]
    consola["🖥️<br/>Consola del servidor<br/>(crear_admin.py)"]

    subgraph panel["Panel web de administración (/panel)"]
        direction TB
        login(["Iniciar sesión<br/>(rol ADMIN, sesión de 8 h)"])
        resumen(["Ver resumen<br/>(recaudación, pasos, rechazos)"])
        pasos(["Consultar pasos por la barrera<br/>(filtros y búsqueda)"])
        usuarios(["Gestionar usuarios"])
        ajuste(["Ajustar saldo<br/>(con motivo)"])
        cuenta(["Activar o desactivar cuenta"])
        tarjeta(["Bloquear o desbloquear tarjeta"])
        garitas(["Gestionar garitas"])
        tarifas(["Definir tarifas<br/>por tipo de vehículo"])
        abrir(["Abrir barrera manualmente"])
        auditoria(["Consultar auditoría"])
        registrar(["Registrar acción en la auditoría"])
        dar(["Dar o quitar rol de administrador"])
    end

    admin --- login
    admin --- resumen
    admin --- pasos
    admin --- usuarios
    admin --- garitas
    admin --- abrir
    admin --- auditoria

    usuarios <-.-|«extend»| ajuste
    usuarios <-.-|«extend»| cuenta
    usuarios <-.-|«extend»| tarjeta
    garitas -.->|«include»| tarifas
    ajuste -.->|«include»| registrar
    cuenta -.->|«include»| registrar
    tarjeta -.->|«include»| registrar
    tarifas -.->|«include»| registrar
    abrir -.->|«include»| registrar

    abrir --- esp
    consola --- dar

    classDef actor fill:#fff,stroke:#333,color:#111
    classDef caso fill:#e8f1fb,stroke:#2b6cb0,color:#111
    classDef alt fill:#fdecea,stroke:#c53030,color:#111
    class admin,esp,consola actor
    class login,resumen,pasos,usuarios,ajuste,cuenta,tarjeta,garitas,tarifas,auditoria,registrar,dar caso
    class abrir alt
    style panel fill:#fafafa,stroke:#555,stroke-width:2px
```

</details>
