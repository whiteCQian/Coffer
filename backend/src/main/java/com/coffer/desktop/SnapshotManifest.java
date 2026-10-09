package com.coffer.desktop;

import java.util.List;
import java.util.Map;

record SnapshotManifest(int formatVersion,String backupId,String createdAt,String h2Version,int schemaVersion,
                        DesktopDataDirectory.Identity identity,String sourceDataRoot,String sourceLibraryRoot,
                        List<Entry> entries,Map<String,TableFact> tables,Map<String,Object> configuration) {
    record Entry(String path,long size,String sha256,String modifiedTime) { }
    record TableFact(long rows,String sha256) { }
}
