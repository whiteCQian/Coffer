package com.coffer.desktop;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BackupCipherTest {
    private static final char[] PASSWORD="strong-backup-passphrase-中文".toCharArray();
    @Test void authenticatedChunksRoundTripLargePayloadAndRejectTamperingTruncationOrWrongPassword() throws Exception {
        byte[] body=new byte[BackupCipher.CHUNK*2+537];new Random(35).nextBytes(body);var encrypted=new ByteArrayOutputStream();
        try(var output=BackupCipher.encrypt(encrypted,PASSWORD)){output.write(body);}
        byte[] payload=encrypted.toByteArray();var restored=new ByteArrayOutputStream();BackupCipher.decrypt(new ByteArrayInputStream(payload),restored,PASSWORD);
        assertThat(restored.toByteArray()).isEqualTo(body);
        assertThatThrownBy(()->BackupCipher.decrypt(new ByteArrayInputStream(payload),new ByteArrayOutputStream(),"wrong-but-long-password".toCharArray())).isInstanceOf(Exception.class);
        byte[] broken=payload.clone();broken[broken.length/2]^=0x40;
        assertThatThrownBy(()->BackupCipher.decrypt(new ByteArrayInputStream(broken),new ByteArrayOutputStream(),PASSWORD)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->BackupCipher.decrypt(new ByteArrayInputStream(Arrays.copyOf(payload,payload.length-28)),new ByteArrayOutputStream(),PASSWORD)).isInstanceOf(Exception.class);
    }
}
