package com.coffer.desktop;

import java.nio.file.*;
import java.util.*;

/** Local OS-owner maintenance: secrets arrive on stdin, never in command arguments, environment or logs. */
public final class DesktopMaintenanceMain {
    public record Response(int code,Object data,String error) { }
    public record Request(String action,String dataDirectory,String libraryDirectory,String destinationDirectory,String archive,String targetDirectory,String password,int targetSchema,Map<String,Object> configuration) { }
    public static int run() {
        char[] password=null;
        try {
            byte[] bytes=System.in.readNBytes(128*1024+1);if(bytes.length>128*1024)throw new IllegalArgumentException("MAINTENANCE_REQUEST_TOO_LARGE");
            var request=DesktopBackupService.JSON.readValue(bytes,Request.class);Arrays.fill(bytes,(byte)0);
            password=request.password()==null?null:request.password().toCharArray();var service=new DesktopBackupService();Object result;
            Path data=request.dataDirectory()==null?null:Path.of(request.dataDirectory()).toAbsolutePath().normalize();
            Path library=request.libraryDirectory()==null && data!=null?data.resolve("library"):request.libraryDirectory()==null?null:Path.of(request.libraryDirectory()).toAbsolutePath().normalize();
            if(data!=null && Files.exists(DesktopUpgradeService.journal(data)) && !Set.of("recover-upgrade","commit-upgrade").contains(request.action()))throw new IllegalStateException("UPGRADE_RECOVERY_REQUIRED");
            switch(request.action()) {
                case "backup","snapshot" -> result=service.backup(data,library,Path.of(request.destinationDirectory()),password,request.action().equals("snapshot"),request.configuration());
                case "verify" -> result=service.inspect(Path.of(request.archive()),password,Path.of(request.targetDirectory()));
                case "restore" -> result=service.restore(Path.of(request.archive()),password,Path.of(request.targetDirectory()));
                case "upgrade" -> result=new DesktopUpgradeService().upgrade(data,library,password,request.targetSchema());
                case "recover-upgrade" -> result=new DesktopUpgradeService().recover(data);
                case "commit-upgrade" -> {new DesktopUpgradeService().commitHealthy(data);result=Map.of("status","UPGRADE_COMMITTED");}
                default -> throw new IllegalArgumentException("MAINTENANCE_ACTION_INVALID");
            }
            respond(new Response(0,result,null));return 0;
        } catch(Exception failed) {
            String message=failed.getMessage();String code=failed instanceof DesktopStartupException startup?startup.reason().name():message!=null && message.matches("[A-Z][A-Z0-9_]{3,80}")?message:failed instanceof javax.crypto.AEADBadTagException?"BACKUP_AUTHENTICATION_FAILED":"MAINTENANCE_FAILED";
            try{respond(new Response(1,null,code));}catch(Exception ignored){}return 1;
        } finally {if(password!=null)Arrays.fill(password,'\0');}
    }
    private static void respond(Response response) throws java.io.IOException {
        // Windows' native stdout encoding may differ from file.encoding; IPC always uses UTF-8 bytes.
        System.out.write(DesktopBackupService.JSON.writeValueAsBytes(response));System.out.write('\n');System.out.flush();
    }
}
