package com.coffer.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Drive letters are not physical media. Unknown evidence is never promoted to an independent copy. */
final class IndependentStorage {
    record Evidence(boolean independent,String kind,String identityHash) { }
    static Evidence evaluate(Path data,Path library,Path destination) throws Exception {
        String selected=destination.toAbsolutePath().toString();
        if(selected.startsWith("\\\\")) {
            String host=selected.substring(2).split("\\\\",2)[0];
            for(Path source:List.of(data,library)){String location=source.toAbsolutePath().toString();if(location.startsWith("\\\\") && location.substring(2).split("\\\\",2)[0].equalsIgnoreCase(host))return new Evidence(false,"SAME_REMOTE_STORAGE_HOST","");}
            var local=new HashSet<InetAddress>();var adapters=NetworkInterface.getNetworkInterfaces();
            while(adapters.hasMoreElements())local.addAll(Collections.list(adapters.nextElement().getInetAddresses()));
            for(var address:InetAddress.getAllByName(host))if(address.isAnyLocalAddress() || address.isLoopbackAddress() || local.contains(address))return new Evidence(false,"SAME_HOST_NETWORK_SHARE","");
            return new Evidence(true,"REMOTE_STORAGE_HOST",SnapshotDatabase.sha256(host.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))return new Evidence(false,"UNVERIFIED_DEVICE","");
        var roots=List.of(data.toAbsolutePath().getRoot().toString(),library.toAbsolutePath().getRoot().toString(),destination.toAbsolutePath().getRoot().toString());
        if(roots.stream().anyMatch(root->!root.matches("[A-Za-z]:\\\\")))return new Evidence(false,"UNVERIFIED_DEVICE","");
        String letters=roots.stream().map(root->root.substring(0,1)).distinct().collect(java.util.stream.Collectors.joining(","));
        String windows=System.getenv().getOrDefault("SystemRoot","C:\\Windows");
        var process=new ProcessBuilder(Path.of(windows,"System32/WindowsPowerShell/v1.0/powershell.exe").toString(),"-NoProfile","-NonInteractive","-Command",
                "$ErrorActionPreference='Stop'; $records=@(foreach($letter in $env:COFFER_MEDIA_LETTERS.Split(',')){ $partition=Get-Partition -DriveLetter $letter; $disk=Get-Disk -Number $partition.DiskNumber; [pscustomobject]@{letter=$letter;identity=$disk.UniqueId;number=$disk.Number} }); ConvertTo-Json -InputObject $records -Compress")
                .redirectErrorStream(true);process.environment().put("COFFER_MEDIA_LETTERS",letters);
        var child=process.start();if(!child.waitFor(15,TimeUnit.SECONDS)){child.destroyForcibly();return new Evidence(false,"UNVERIFIED_DEVICE","");}
        byte[] output=child.getInputStream().readNBytes(65536);if(child.exitValue()!=0)return new Evidence(false,"UNVERIFIED_DEVICE","");
        var values=new ObjectMapper().readTree(output);var devices=new HashMap<String,String>();
        for(var entry:values){String id=entry.path("identity").asText();if(id.isBlank())return new Evidence(false,"UNVERIFIED_DEVICE","");devices.put(entry.path("letter").asText().toUpperCase(Locale.ROOT),id);}
        String target=devices.get(roots.get(2).substring(0,1).toUpperCase(Locale.ROOT));
        if(target==null)return new Evidence(false,"UNVERIFIED_DEVICE","");
        boolean separate=!Objects.equals(target,devices.get(roots.get(0).substring(0,1).toUpperCase(Locale.ROOT))) && !Objects.equals(target,devices.get(roots.get(1).substring(0,1).toUpperCase(Locale.ROOT)));
        return new Evidence(separate,separate?"INDEPENDENT_PHYSICAL_DISK":"SAME_PHYSICAL_DISK",SnapshotDatabase.sha256(target.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
