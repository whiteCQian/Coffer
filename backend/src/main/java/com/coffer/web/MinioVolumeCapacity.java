package com.coffer.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.time.*;

/** Reads only the bounded content-free receipt written by a probe on the actual MinIO volume. */
@Component @RequiredArgsConstructor
public class MinioVolumeCapacity {
    public record Sample(Instant checkedAt, long totalBytes, long freeBytes) { }
    private final WebLimitProperties properties;
    private final ObjectMapper json;
    public Sample sample() {
        try {
            Path file = Path.of(properties.getCapacityReceipt());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4096) throw new IllegalStateException();
            var node = json.readTree(Files.readString(file));
            var value = new Sample(Instant.parse(node.path("checkedAt").asText()), node.path("totalBytes").asLong(-1), node.path("freeBytes").asLong(-1));
            Instant now = Instant.now();
            if (value.checkedAt().isAfter(now.plusSeconds(5)) || Duration.between(value.checkedAt(), now).getSeconds() > properties.getCapacityMaxAgeSeconds()
                    || value.totalBytes() <= 0 || value.freeBytes() < 0 || value.freeBytes() > value.totalBytes()) throw new IllegalStateException();
            return value;
        } catch (Exception invalid) { throw new WebLimitException(503, "存储卷容量暂时无法核实，请稍后重试或联系管理员"); }
    }
}
