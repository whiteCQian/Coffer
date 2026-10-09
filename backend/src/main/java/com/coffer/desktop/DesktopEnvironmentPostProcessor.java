package com.coffer.desktop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.coffer.desktop.DesktopStartupException.Reason.*;

/** Bind desktop paths before logging and before any JDBC connection can create a database. */
public final class DesktopEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!env.matchesProfiles("desktop")) return;
        if (env.matchesProfiles("dev", "prod", "test")) throw new DesktopStartupException(PROFILE_CONFLICT);
        Path root = resolveDirectory(env);
        if(java.nio.file.Files.exists(DesktopUpgradeService.journal(root),java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && !DesktopUpgradeService.allowedHealthProbe(root,env.getProperty("COFFER_UPGRADE_HEALTH_CHECK_ID","")))
            throw new DesktopStartupException(UPGRADE_RECOVERY_REQUIRED);
        boolean initialize = env.getProperty("coffer.desktop.initialize", Boolean.class, false);
        String selected = env.getProperty("coffer.desktop.library-directory", env.getProperty("COFFER_DESKTOP_LIBRARY_DIR", root.resolve("library").toString()));
        Path library;
        try {
            library = Path.of(selected);
            if (!library.isAbsolute() || selected.contains(";") || selected.contains("\n") || selected.contains("\r"))
                throw new IllegalArgumentException();
            Path code = Path.of(com.coffer.CofferApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path install = java.nio.file.Files.isDirectory(code) ? code : code.getParent();
            if (library.normalize().startsWith(install.toAbsolutePath().normalize())) throw new IllegalArgumentException();
        } catch (Exception invalid) { throw new DesktopStartupException(INVALID_DIRECTORY); }
        Map<String, Object> fixed = new LinkedHashMap<>();
        fixed.put("coffer.desktop.data-directory", root.toString());
        fixed.put("coffer.storage.local.root", library.normalize().toString());
        fixed.put("coffer.import.inbox.directory", library.normalize().resolve("users/{ownerId}/inbox").toString());
        fixed.put("COFFER_LOG_DIR", root.resolve("logs").toString());
        fixed.put("spring.datasource.url", "jdbc:h2:file:" + root.resolve("database/coffer").toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0" + (initialize ? "" : ";IFEXISTS=TRUE"));
        fixed.put("spring.datasource.driver-class-name", "org.h2.Driver");
        fixed.put("server.address", "127.0.0.1");
        fixed.put("spring.h2.console.enabled", false);
        fixed.put("spring.devtools.restart.enabled", false);
        fixed.put("spring.devtools.livereload.enabled", false);
        fixed.put("spring.jpa.hibernate.ddl-auto", "validate");
        fixed.put("spring.jpa.show-sql", false);
        fixed.put("spring.flyway.enabled", true);
        fixed.put("spring.flyway.locations", "classpath:db/migration/h2");
        fixed.put("spring.flyway.validate-on-migrate", true);
        fixed.put("spring.flyway.baseline-on-migrate", false);
        fixed.put("spring.flyway.clean-disabled", true);
        fixed.put("springdoc.api-docs.enabled", false);
        fixed.put("springdoc.swagger-ui.enabled", false);
        env.getPropertySources().addFirst(new MapPropertySource("desktopProductionContract", fixed));
        // Validate/init before the logging system can create a logs directory in a missing data root.
        var directory = DesktopDataDirectory.open(root, initialize, library,
                env.getProperty("coffer.desktop.rebind-library", Boolean.class, false));
        try {
            if (!initialize && env.getProperty("coffer.desktop.shell", Boolean.class, false)) DesktopUpgradeBackup.guard(directory, env);
            String configuredKey = env.getProperty("COFFER_SECRET_KEY", "");
            if (!configuredKey.isBlank() && !java.security.MessageDigest.isEqual(
                    configuredKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    directory.masterKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                throw new DesktopStartupException(KEY_INVALID);
            env.getPropertySources().addFirst(new MapPropertySource("desktopLocalSecrets", Map.of(
                    "COFFER_SECRET_KEY", directory.masterKey(), "coffer.auth.initial-admin-token", directory.setupToken())));
            application.addInitializers(new DesktopContextInitializer(directory));
            application.addListeners((org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationFailedEvent>) event -> {
                // A failed start must close JDBC resources before letting another process acquire the lock.
                try {
                    if (event.getApplicationContext() != null) event.getApplicationContext().close();
                } finally { directory.close(); }
            });
        } catch (RuntimeException failed) { directory.close(); throw failed; }
    }

    static Path resolveDirectory(ConfigurableEnvironment env) {
        String configured = env.getProperty("coffer.desktop.data-directory",
                env.getProperty("COFFER_DESKTOP_USER_DATA_DIR", ""));
        if (configured.isBlank()) {
            String appData = env.getProperty("LOCALAPPDATA", "");
            configured = appData.isBlank() ? Path.of(System.getProperty("user.home"), ".coffer").toString()
                    : Path.of(appData, "Coffer").toString();
        }
        try {
            Path root = Path.of(configured);
            if (!root.isAbsolute() || configured.contains(";") || configured.contains("\n") || configured.contains("\r"))
                throw new DesktopStartupException(INVALID_DIRECTORY);
            root = root.normalize();
            Path code = Path.of(com.coffer.CofferApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path install = java.nio.file.Files.isDirectory(code) ? code : code.getParent();
            if (root.startsWith(install.toAbsolutePath().normalize())) throw new DesktopStartupException(INVALID_DIRECTORY);
            return root;
        } catch (DesktopStartupException fixed) { throw fixed; }
        catch (Exception invalid) { throw new DesktopStartupException(INVALID_DIRECTORY); }
    }
}
