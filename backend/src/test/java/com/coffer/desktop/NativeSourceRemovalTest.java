package com.coffer.desktop;

import com.coffer.file.application.LocalImportSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;

class NativeSourceRemovalTest {
    @TempDir Path temp;
    private String sha(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes())); }
    @Test void removesExactSourceAndResumesPersistedClaimWithoutRemovingAReplacement() throws Exception {
        Path source=Files.writeString(temp.resolve("source.txt"),"original"), claim=temp.resolve(".coffer-import-test");
        var attrs=Files.readAttributes(source,java.nio.file.attribute.BasicFileAttributes.class);
        Files.move(source,claim); Files.writeString(source,"replacement");
        NativeSourceRemoval.remove(source,claim,attrs.size(),attrs.lastModifiedTime().toMillis(),LocalImportSource.key(attrs),sha("original"));
        assertThat(source).hasContent("replacement"); assertThat(claim).doesNotExist();
    }
    @Test void sameSizeSameTimestampChangedBodyAndOccupiedSourceAreRetained() throws Exception {
        Path source=Files.writeString(temp.resolve("source.txt"),"original"), claim=temp.resolve(".coffer-import-test");
        var attrs=Files.readAttributes(source,java.nio.file.attribute.BasicFileAttributes.class);
        Files.writeString(source,"changed!"); Files.setLastModifiedTime(source,attrs.lastModifiedTime());
        assertThatThrownBy(()->NativeSourceRemoval.remove(source,claim,attrs.size(),attrs.lastModifiedTime().toMillis(),LocalImportSource.key(attrs),sha("original"))).isInstanceOf(RuntimeException.class);
        assertThat(source).hasContent("changed!"); assertThat(claim).doesNotExist();
        Files.writeString(source,"original"); Files.setLastModifiedTime(source,attrs.lastModifiedTime());
        try(var channel=FileChannel.open(source,StandardOpenOption.WRITE);var lock=channel.lock()) {
            assertThatThrownBy(()->NativeSourceRemoval.remove(source,claim,attrs.size(),attrs.lastModifiedTime().toMillis(),LocalImportSource.key(attrs),sha("original"))).isInstanceOf(RuntimeException.class);
        }
        assertThat(source).exists();
        NativeSourceRemoval.remove(source,claim,attrs.size(),attrs.lastModifiedTime().toMillis(),LocalImportSource.key(attrs),sha("original"));
        assertThat(source).doesNotExist(); assertThat(claim).doesNotExist();
    }
}
