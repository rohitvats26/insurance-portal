-- Run once against the RDS instance (e.g. via a bastion / EC2 instance in the
-- same VPC, since the DB has no public access).

CREATE DATABASE IF NOT EXISTS insurance_portal;
USE insurance_portal;

CREATE TABLE IF NOT EXISTS uploads (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_name         VARCHAR(512) NOT NULL,
    content_type      VARCHAR(128) NOT NULL,
    upload_timestamp  TIMESTAMP NOT NULL,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
