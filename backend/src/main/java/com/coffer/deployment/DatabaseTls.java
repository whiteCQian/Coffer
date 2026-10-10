package com.coffer.deployment;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.*;
/** Database CA/password are scoped to Connector/J, independent of MinIO and backup encryption. */
public final class DatabaseTls {
    public static void configure(HikariDataSource source) {
        try {
            if (source.getJdbcUrl() == null || !source.getJdbcUrl().contains("sslMode=VERIFY_IDENTITY") || source.getJdbcUrl().contains("allowPublicKeyRetrieval=true"))
                throw new IllegalStateException("Production database requires TLS identity verification");
            if ("root".equalsIgnoreCase(source.getUsername()) || source.getPassword() == null || source.getPassword().isBlank()) throw new IllegalStateException();
            source.addDataSourceProperty("trustCertificateKeyStoreUrl", "file:/run/secrets/db_truststore");
            source.addDataSourceProperty("trustCertificateKeyStoreType", "PKCS12");
            source.addDataSourceProperty("trustCertificateKeyStorePassword", Files.readString(Path.of("/run/secrets/db_trust_password")).trim());
            source.addDataSourceProperty("fallbackToSystemTrustStore", "false");
        } catch (Exception failure) { throw new IllegalStateException("Database TLS trust configuration unavailable"); }
    }
}
