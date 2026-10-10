package com.coffer.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryPointMainTest {
    private ObjectNode point() throws Exception {
        return (ObjectNode)new ObjectMapper().readTree("""
            {"formatVersion":2,"tables":{"ledger":{"rows":2,"sha256":"rows","owners":{"1":1,"2":1}}},
             "files":[{"ownerId":1,"sha256":"body"}],"objects":[{"versionId":"v1","sha256":"body"}],
             "masterKeyId":"master","authenticatedSecrets":2,"decryptedSecretDigest":"keys"}
            """);
    }
    @Test void timeDoesNotChangeRecoveryProof() throws Exception {
        var expected=point();var actual=point();actual.put("capturedAt","later");
        assertDoesNotThrow(()->RecoveryPointMain.compare(expected,actual));
    }
    @Test void rejectsChangesInOwnersBodiesVersionsLedgersAndKeys() throws Exception {
        var expected=point();
        for(String field:new String[]{"tables","files","objects","masterKeyId","authenticatedSecrets","decryptedSecretDigest"}) {
            var changed=point();changed.put(field,"tampered");
            assertThrows(IllegalStateException.class,()->RecoveryPointMain.compare(expected,changed),field);
            var missing=point();missing.remove(field);
            assertThrows(IllegalStateException.class,()->RecoveryPointMain.compare(missing,missing),field);
        }
    }
    @Test void jdbcLongAndParsedIntegerRepresentTheSameRecoveryPoint() throws Exception {
        var expected=point();var actual=point();
        ((ObjectNode)actual.path("files").get(0)).put("ownerId",1L);
        ((ObjectNode)actual.path("tables").path("ledger").path("owners")).put("1",1L);
        actual.put("authenticatedSecrets",2L);
        assertDoesNotThrow(()->RecoveryPointMain.compare(expected,actual));
    }
    @Test void rejectsUnsupportedFormat() throws Exception {
        var expected=point();var actual=point();actual.put("formatVersion",3);
        assertThrows(IllegalStateException.class,()->RecoveryPointMain.compare(expected,actual));
    }
}
