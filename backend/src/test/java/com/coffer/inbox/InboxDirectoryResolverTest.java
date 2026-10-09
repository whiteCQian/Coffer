package com.coffer.inbox;

import com.coffer.auth.service.TenantContext;
import com.coffer.config.InboxImportProperties;
import com.coffer.desktop.DesktopLibraryLayout;
import com.coffer.inbox.application.InboxDirectoryResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.*;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InboxDirectoryResolverTest {
    @TempDir Path temp;
    @BeforeEach void owner() { TenantContext.set(7L); }
    @AfterEach void clear() { TenantContext.clear(); }
    private InboxDirectoryResolver resolver(String template) {
        var properties = new InboxImportProperties(); properties.setEnabled(true); properties.setDirectory(template);
        return new InboxDirectoryResolver(properties, mock(ObjectProvider.class));
    }
    @Test void directoryTemplateMustContainAnActualOwnerSegmentAndNoTraversal() {
        assertThat(InboxDirectoryResolver.resolveTemplate(temp.resolve("{ownerId}/inbox").toString())).isEqualTo(temp.resolve("7/inbox"));
        assertThat(InboxDirectoryResolver.resolveTemplate(temp.resolve("prefix-{ownerId}").toString())).isNull();
        assertThat(InboxDirectoryResolver.resolveTemplate(temp.resolve("{ownerId}/../shared").toString())).isNull();
        assertThat(InboxDirectoryResolver.resolveTemplate("relative/{ownerId}")).isNull();
    }
    @Test void foreignOwnerAndNestedExternalSourcesAreDenied() throws Exception {
        Path a = Files.createDirectories(temp.resolve("7")), b = Files.createDirectories(temp.resolve("8"));
        Path allowed = Files.writeString(a.resolve("plain.txt"), "a"), foreign = Files.writeString(b.resolve("foreign.txt"), "b");
        var resolver = resolver(temp.resolve("{ownerId}").toString());
        assertThat(resolver.requireSource(allowed)).isEqualTo(allowed);
        assertThatThrownBy(() -> resolver.requireSource(foreign)).isInstanceOf(SecurityException.class);
        Path nested = Files.createDirectories(a.resolve("nested")); Path file = Files.writeString(nested.resolve("plain.txt"), "external");
        assertThatThrownBy(() -> resolver.requireSource(file)).isInstanceOf(SecurityException.class);
        assertThat(Files.readString(foreign)).isEqualTo("b");
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void junctionInboxCannotRedirectAReadToAnUncontrolledDirectory() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "private"); Path junction = temp.resolve("7");
        Process mklink = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", junction.toString(), outside.toString())
                .redirectErrorStream(true).start();
        try (var output = mklink.getInputStream()) { output.readAllBytes(); }
        assertThat(mklink.waitFor()).isZero();
        try {
            assertThatThrownBy(() -> resolver(temp.resolve("{ownerId}").toString()).requireSource(junction.resolve("secret.txt")))
                    .isInstanceOf(SecurityException.class);
            assertThat(Files.readString(outside.resolve("secret.txt"))).isEqualTo("private");
        } finally { Files.deleteIfExists(junction); }
    }
}
