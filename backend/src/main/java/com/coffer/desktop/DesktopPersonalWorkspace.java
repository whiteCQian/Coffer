package com.coffer.desktop;

import com.coffer.auth.domain.AuthRole;
import com.coffer.auth.service.AuthPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

/** One local library identity, never an interactive account or a login credential. */
@Component @Profile("desktop") @DependsOnDatabaseInitialization
@ConditionalOnExpression("${coffer.desktop.personal:${coffer.desktop.shell:false}}")
public class DesktopPersonalWorkspace {
    private final AuthPrincipal principal;
    public DesktopPersonalWorkspace(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                   @org.springframework.beans.factory.annotation.Value("${COFFER_DESKTOP_LAUNCH_TOKEN:}") String capability) {
        if (!capability.matches("[0-9a-f]{64}")) throw new IllegalStateException("个人文件库的桌面启动凭据缺失");
        principal = new TransactionTemplate(transactions).execute(status -> {
            jdbc.queryForObject("SELECT id FROM auth_setup_lock WHERE id=1 FOR UPDATE", Long.class);
            var owners = jdbc.queryForList("SELECT id,enabled FROM app_user WHERE role='USER' ORDER BY id");
            if (owners.size() > 1) throw new DesktopStartupException(DesktopStartupException.Reason.MULTIPLE_LEGACY_OWNERS);
            long id;
            if (owners.isEmpty()) {
                String internalName = "local-" + UUID.randomUUID();
                jdbc.update("INSERT INTO app_user(username,password_hash,role,enabled,created_at,updated_at) VALUES(?,?,'USER',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                        internalName, "{noop}" + UUID.randomUUID() + UUID.randomUUID());
                id = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, internalName);
            } else {
                if (!Boolean.TRUE.equals(owners.get(0).get("enabled"))) throw new DesktopStartupException(DesktopStartupException.Reason.LEGACY_OWNER_DISABLED);
                id = ((Number) owners.get(0).get("id")).longValue();
            }
            // Retain legacy owner keys so existing bodies, references and encrypted secrets remain readable.
            return new AuthPrincipal(id, "个人文件库", null, AuthRole.USER, true);
        });
    }
    public AuthPrincipal principal() { return principal; }
}
