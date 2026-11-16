-- =====================================================================
--  Peaje IoT - Script de creación COMPLETA de la base de datos
--  ATENCIÓN: borra la base de datos existente y TODOS sus datos.
--
--  Uso:  mysql -u root -p < db/schema.sql
--  Requiere MySQL 8.0.16+ (o MariaDB 10.4+) por las restricciones CHECK.
-- =====================================================================

DROP DATABASE IF EXISTS peaje_iot;
CREATE DATABASE peaje_iot CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE peaje_iot;

-- ---------------------------------------------------------------------
-- Tipos de vehículo (catálogo). El código es el que envía la app al registrarse.
-- ---------------------------------------------------------------------
CREATE TABLE tipos_vehiculo (
    id           TINYINT UNSIGNED NOT NULL AUTO_INCREMENT,
    codigo       VARCHAR(20)      NOT NULL,
    nombre       VARCHAR(50)      NOT NULL,
    descripcion  VARCHAR(150)     NULL,
    orden        TINYINT UNSIGNED NOT NULL DEFAULT 0,   -- orden en la lista de la app
    activo       TINYINT(1)       NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tipos_vehiculo_codigo (codigo)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Usuarios, saldo y vehículo
-- ---------------------------------------------------------------------
CREATE TABLE usuarios (
    id               INT UNSIGNED     NOT NULL AUTO_INCREMENT,
    nombre           VARCHAR(80)      NOT NULL,
    apellido         VARCHAR(80)      NOT NULL,
    correo           VARCHAR(190)     NOT NULL,
    password_hash    VARCHAR(100)     NOT NULL,
    patente          VARCHAR(10)      NOT NULL,         -- mayúsculas, sin guiones ni espacios
    tipo_vehiculo_id TINYINT UNSIGNED NOT NULL,
    saldo            DECIMAL(12,2)    NOT NULL DEFAULT 0.00,
    activo           TINYINT(1)       NOT NULL DEFAULT 1,
    rol              ENUM('USUARIO','ADMIN') NOT NULL DEFAULT 'USUARIO',  -- ADMIN: acceso al panel web
    creado_en        DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actualizado_en   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_usuarios_correo (correo),
    UNIQUE KEY uk_usuarios_patente (patente),
    KEY idx_usuarios_tipo (tipo_vehiculo_id),
    CONSTRAINT fk_usuarios_tipo_vehiculo FOREIGN KEY (tipo_vehiculo_id)
        REFERENCES tipos_vehiculo (id),
    CONSTRAINT ck_usuarios_saldo CHECK (saldo >= 0)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Credenciales para cobro por UID (opción 2, lector RC522)
--   VIRTUAL : UID generado por el sistema al registrarse (uid_nfc en la app)
--   TARJETA : tarjeta física RFID/NFC vinculada por el usuario
-- ---------------------------------------------------------------------
CREATE TABLE credenciales (
    id          INT UNSIGNED NOT NULL AUTO_INCREMENT,
    usuario_id  INT UNSIGNED NOT NULL,
    tipo        ENUM('VIRTUAL','TARJETA') NOT NULL,
    uid         VARCHAR(20)  NOT NULL,          -- hexadecimal en mayúsculas, sin separadores
    alias       VARCHAR(50)  NULL,
    activa      TINYINT(1)   NOT NULL DEFAULT 1,
    creada_en   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_credenciales_uid (uid),
    KEY idx_credenciales_usuario (usuario_id),
    CONSTRAINT fk_credenciales_usuario FOREIGN KEY (usuario_id)
        REFERENCES usuarios (id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Garitas (el código debe coincidir con el topic MQTT y con el usuario MQTT del ESP32)
-- ---------------------------------------------------------------------
CREATE TABLE garitas (
    id         INT UNSIGNED  NOT NULL AUTO_INCREMENT,
    codigo     VARCHAR(50)   NOT NULL,
    nombre     VARCHAR(100)  NOT NULL,
    activa     TINYINT(1)    NOT NULL DEFAULT 1,
    creada_en  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_garitas_codigo (codigo)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Tarifa por garita y tipo de vehículo.
-- Si falta la combinación, el paso se deniega con estado TARIFA_NO_DEFINIDA.
-- ---------------------------------------------------------------------
CREATE TABLE tarifas (
    garita_id         INT UNSIGNED     NOT NULL,
    tipo_vehiculo_id  TINYINT UNSIGNED NOT NULL,
    monto             DECIMAL(10,2)    NOT NULL,
    actualizada_en    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (garita_id, tipo_vehiculo_id),
    CONSTRAINT fk_tarifas_garita FOREIGN KEY (garita_id)
        REFERENCES garitas (id) ON DELETE CASCADE,
    CONSTRAINT fk_tarifas_tipo FOREIGN KEY (tipo_vehiculo_id)
        REFERENCES tipos_vehiculo (id) ON DELETE CASCADE,
    CONSTRAINT ck_tarifas_monto CHECK (monto >= 0)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Registro de cada paso por el peaje (aprobado o rechazado)
-- ---------------------------------------------------------------------
CREATE TABLE transacciones_peaje (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    usuario_id      INT UNSIGNED    NULL,
    credencial_id   INT UNSIGNED    NULL,
    metodo          ENUM('UID','TOKEN') NOT NULL,
    uid_detectado   VARCHAR(64)     NULL,
    garita          VARCHAR(50)     NOT NULL,
    carril          VARCHAR(50)     NOT NULL,
    patente         VARCHAR(10)     NULL,       -- vehículo al momento del paso
    tipo_vehiculo   VARCHAR(20)     NULL,       -- código de tipos_vehiculo al momento del paso
    saldo           DECIMAL(12,2)   NULL,       -- saldo antes del cobro
    tarifa_cobrada  DECIMAL(10,2)   NOT NULL,
    saldo_restante  DECIMAL(12,2)   NULL,
    estado          ENUM(
                        'APROBADO',
                        'SALDO_INSUFICIENTE',
                        'USUARIO_NO_ENCONTRADO',
                        'TRANSACCION_INVALIDA',
                        'CREDENCIAL_INACTIVA',
                        'LECTURA_DUPLICADA',
                        'METODO_DESHABILITADO',
                        'TOKEN_INVALIDO',
                        'TOKEN_EXPIRADO',
                        'TOKEN_REUTILIZADO',
                        'TARIFA_NO_DEFINIDA'
                    ) NOT NULL,
    lectura_id      VARCHAR(64)     NULL,       -- id enviado por el ESP32 (payload JSON)
    fecha_hora      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tx_usuario_fecha (usuario_id, fecha_hora),
    KEY idx_tx_antidoble (usuario_id, garita, estado, fecha_hora),
    KEY idx_tx_garita_fecha (garita, carril, fecha_hora),
    CONSTRAINT fk_tx_usuario FOREIGN KEY (usuario_id)
        REFERENCES usuarios (id) ON DELETE SET NULL,
    CONSTRAINT fk_tx_credencial FOREIGN KEY (credencial_id)
        REFERENCES credenciales (id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Recargas de saldo (auditoría)
-- ---------------------------------------------------------------------
CREATE TABLE recargas (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    usuario_id      INT UNSIGNED    NOT NULL,
    monto           DECIMAL(12,2)   NOT NULL,
    saldo_anterior  DECIMAL(12,2)   NOT NULL,
    saldo_nuevo     DECIMAL(12,2)   NOT NULL,
    fecha_hora      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_recargas_usuario_fecha (usuario_id, fecha_hora),
    CONSTRAINT fk_recargas_usuario FOREIGN KEY (usuario_id)
        REFERENCES usuarios (id) ON DELETE CASCADE,
    CONSTRAINT ck_recargas_monto CHECK (monto > 0)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Tokens HCE ya usados (evita que un token capturado se reutilice).
-- El worker borra periódicamente los vencidos.
-- ---------------------------------------------------------------------
CREATE TABLE tokens_nfc_usados (
    nonce       CHAR(16)     NOT NULL,          -- 8 bytes en hexadecimal
    usuario_id  INT UNSIGNED NOT NULL,
    expira      DATETIME     NOT NULL,
    usado_en    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (nonce),
    KEY idx_tokens_expira (expira),
    CONSTRAINT fk_tokens_usuario FOREIGN KEY (usuario_id)
        REFERENCES usuarios (id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Ajustes de saldo hechos por un administrador desde el panel (positivos o negativos).
-- Saldo de un usuario = recargas + ajustes - cobros aprobados.
-- ---------------------------------------------------------------------
CREATE TABLE ajustes_saldo (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    usuario_id      INT UNSIGNED    NOT NULL,
    admin_id        INT UNSIGNED    NULL,
    monto           DECIMAL(12,2)   NOT NULL,
    saldo_anterior  DECIMAL(12,2)   NOT NULL,
    saldo_nuevo     DECIMAL(12,2)   NOT NULL,
    motivo          VARCHAR(200)    NOT NULL,
    fecha_hora      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ajustes_usuario_fecha (usuario_id, fecha_hora),
    CONSTRAINT fk_ajustes_usuario FOREIGN KEY (usuario_id)
        REFERENCES usuarios (id) ON DELETE CASCADE,
    CONSTRAINT fk_ajustes_admin FOREIGN KEY (admin_id)
        REFERENCES usuarios (id) ON DELETE SET NULL,
    CONSTRAINT ck_ajustes_monto CHECK (monto <> 0)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Auditoría del panel de administración: cada acción que modifica algo.
-- ---------------------------------------------------------------------
CREATE TABLE auditoria_admin (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    admin_id    INT UNSIGNED    NULL,
    accion      VARCHAR(50)     NOT NULL,
    detalle     VARCHAR(500)    NULL,
    ip          VARCHAR(45)     NULL,
    fecha_hora  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_auditoria_fecha (fecha_hora),
    CONSTRAINT fk_auditoria_admin FOREIGN KEY (admin_id)
        REFERENCES usuarios (id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Datos iniciales
-- Las garitas deben coincidir con los usuarios MQTT de MQTT_DISPOSITIVOS en el .env
-- Los usuarios se crean desde la app (POST /registro).
-- Para dar acceso al panel web: python crear_admin.py <correo>
-- ---------------------------------------------------------------------
INSERT INTO tipos_vehiculo (codigo, nombre, descripcion, orden) VALUES
    ('MOTO',      'Motocicleta',          'Motos y motonetas',                       1),
    ('AUTO',      'Automóvil',            'Autos, station wagon y SUV',              2),
    ('CAMIONETA', 'Camioneta',            'Camionetas y furgones',                   3),
    ('BUS',       'Bus',                  'Buses y minibuses',                       4),
    ('CAMION',    'Camión',               'Camiones de 2 o más ejes',                5);

INSERT INTO garitas (codigo, nombre, activa) VALUES
    ('garita_norte', 'Garita Norte', 1);

-- Tarifas de cada garita por tipo de vehículo (ajusta los montos)
INSERT INTO tarifas (garita_id, tipo_vehiculo_id, monto)
SELECT g.id, t.id,
       CASE t.codigo
           WHEN 'MOTO'      THEN  450.00
           WHEN 'AUTO'      THEN  900.00
           WHEN 'CAMIONETA' THEN 1100.00
           WHEN 'BUS'       THEN 1800.00
           WHEN 'CAMION'    THEN 2500.00
       END
FROM garitas g CROSS JOIN tipos_vehiculo t
WHERE g.codigo = 'garita_norte';

-- ---------------------------------------------------------------------
-- (Opcional) Usuario de base de datos para la API y el worker.
-- Descomenta, cambia la clave y deja la misma en DB_USER / DB_PASSWORD del .env.
-- ---------------------------------------------------------------------
CREATE USER IF NOT EXISTS 'peaje_app'@'localhost' IDENTIFIED BY 'CAMBIAR_ESTA_CLAVE';
CREATE USER IF NOT EXISTS 'peaje_app'@'127.0.0.1' IDENTIFIED BY 'CAMBIAR_ESTA_CLAVE';
GRANT SELECT, INSERT, UPDATE, DELETE ON peaje_iot.* TO 'peaje_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON peaje_iot.* TO 'peaje_app'@'127.0.0.1';
FLUSH PRIVILEGES;
