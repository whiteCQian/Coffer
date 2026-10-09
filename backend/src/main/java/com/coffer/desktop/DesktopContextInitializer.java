package com.coffer.desktop;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Acquire the library lock and validate all files before the datasource is instantiated. */
public final class DesktopContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private final DesktopDataDirectory directory;
    DesktopContextInitializer(DesktopDataDirectory directory) { this.directory = directory; }
    @Override public void initialize(ConfigurableApplicationContext context) {
        try {
            var factory = (DefaultListableBeanFactory) context.getBeanFactory();
            factory.registerSingleton("desktopDataDirectory", directory);
            factory.registerDisposableBean("desktopDataDirectory", directory);
            // Close the database before releasing the process-level lock, including refresh failures.
            factory.registerDependentBean("desktopDataDirectory", "dataSource");
            factory.registerDependentBean("desktopDataDirectory", "entityManagerFactory");
        } catch (RuntimeException failed) { directory.close(); throw failed; }
    }
}
