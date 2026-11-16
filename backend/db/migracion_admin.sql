-- =====================================================================
--  Peaje IoT - Migración: panel de administración
--  Agrega el rol de usuario, los ajustes de saldo y la auditoría a una base
--  creada con una versión anterior de schema.sql. NO borra datos.
--  Se puede ejecutar más de una vez.
--
--  Uso:  mysql -u root -p peaje_iot < db/migracion_admin.sql
--  (MariaDB 10.4+; en MySQL 8 quita "IF NOT EXISTS" de la línea ALTER TABLE)
-- =====================================================================

ALTER TABLE usuarios
    ADD COLUMN IF NOT EXISTS rol ENUM('USUARIO','ADMIN') NOT NULL DEFAULT 'USUARIO' AFTER activo;

CREATE TABLE IF NOT EXISTS ajustes_saldo (
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

CREATE TABLE IF NOT EXISTS auditoria_admin (
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
