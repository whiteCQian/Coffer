package com.coffer.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.util.*;

/** Explicit owner predicates and dependency ordering; no private rows are returned to an administrator. */
@Service @RequiredArgsConstructor
public class AccountDatabasePurger {
    private final JdbcTemplate jdbc;
    private record Table(String name, String ownerColumn, Set<String> parents) { }

    @Transactional
    public void purge(long targetId) {
        var job=jdbc.queryForMap("SELECT * FROM account_deletion_job WHERE target_user_id=? AND status='RUNNING' FOR UPDATE",targetId);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=? AND enabled=FALSE AND deletion_status='RUNNING'",Long.class,targetId)!=1)
            throw new IllegalStateException("账号删除状态已变化");
        String username=jdbc.queryForObject("SELECT username FROM app_user WHERE id=?",String.class,targetId);
        var plan=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Plan>) connection -> plan(connection));
        for (var table:plan.tables()) jdbc.update("DELETE FROM " + quote(table.name(),plan.quote()) + " WHERE " + quote(table.ownerColumn(),plan.quote()) + "=?",targetId);
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?",username);
        if(jdbc.update("DELETE FROM app_user WHERE id=? AND enabled=FALSE AND deletion_status='RUNNING'",targetId)!=1)
            throw new IllegalStateException("账号删除未完成");
        jdbc.update("UPDATE account_deletion_job SET status='DONE',updated_at=CURRENT_TIMESTAMP WHERE target_user_id=?",targetId);
        jdbc.update("INSERT INTO account_audit_log(actor_user_id,actor_username,target_user_id,action,created_at) VALUES(?,?,?,'ACCOUNT_DELETED',CURRENT_TIMESTAMP)",
                job.get("actor_user_id"),job.get("actor_username"),targetId);
    }
    private record Plan(List<Table> tables, String quote) { }
    private Plan plan(Connection c) throws SQLException {
        var metadata=c.getMetaData(); String catalog=c.getCatalog(), schema=c.getSchema();
        Map<String,Table> tables=new TreeMap<>();
        try(var rs=metadata.getTables(catalog,schema,"%",new String[]{"TABLE"})) {
            while(rs.next()) {
                String name=rs.getString("TABLE_NAME"), owner=null;
                try(var columns=metadata.getColumns(catalog,schema,name,"%")) {
                    while(columns.next()) if("owner_id".equalsIgnoreCase(columns.getString("COLUMN_NAME")))owner=columns.getString("COLUMN_NAME");
                }
                if(owner!=null)tables.put(name.toUpperCase(Locale.ROOT),new Table(name,owner,new HashSet<>()));
            }
        }
        for(var table:tables.values())try(var keys=metadata.getImportedKeys(catalog,schema,table.name())) {
            while(keys.next()) {
                String parent=keys.getString("PKTABLE_NAME").toUpperCase(Locale.ROOT);
                if(tables.containsKey(parent)&&!parent.equals(table.name().toUpperCase(Locale.ROOT)))table.parents().add(parent);
            }
        }
        List<Table> ordered=new ArrayList<>(); Set<String> remaining=new TreeSet<>(tables.keySet());
        while(!remaining.isEmpty()) {
            String child=remaining.stream().filter(t -> remaining.stream().noneMatch(other -> tables.get(other).parents().contains(t))).findFirst()
                    .orElseThrow(() -> new IllegalStateException("账号关联数据存在循环依赖"));
            ordered.add(tables.get(child)); remaining.remove(child);
        }
        return new Plan(ordered,metadata.getIdentifierQuoteString().trim());
    }
    private String quote(String identifier,String quote) {
        if(!identifier.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalStateException("数据库标识无效");
        return quote + identifier + quote;
    }
}
