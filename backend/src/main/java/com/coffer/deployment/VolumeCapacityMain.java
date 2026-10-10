package com.coffer.deployment;
import java.nio.file.*;
import java.time.Instant;
/** A network-isolated sidecar sees only volume space; its receipt contains no object names/content. */
public final class VolumeCapacityMain {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("/measure/minio"), output = Path.of("/capacity/minio-capacity.json");
        while (true) {
            try {
                var store = Files.getFileStore(root);
                String sample = "{\"checkedAt\":\"" + Instant.now() + "\",\"totalBytes\":" + store.getTotalSpace() + ",\"freeBytes\":" + store.getUsableSpace() + "}";
                Path stage = Files.createTempFile(output.getParent(), ".capacity-", ".tmp");
                try {
                    Files.writeString(stage, sample);
                    Files.setPosixFilePermissions(stage, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
                    Files.move(stage, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(stage); }
            } catch (Exception unavailable) { System.err.println("MINIO_CAPACITY_SAMPLE_UNAVAILABLE"); if(args.length>0 && args[0].equals("--once")) System.exit(1); }
            if(args.length>0 && args[0].equals("--once")) return;
            Thread.sleep(10000);
        }
    }
}
