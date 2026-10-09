package com.coffer.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Bounded-memory AEAD records, including an authenticated final record; no plaintext archive on backup media. */
final class BackupCipher {
    static final int CHUNK=1024*1024, ITERATIONS=600_000;
    private static final byte[] MAGIC="COFFERBK1".getBytes(StandardCharsets.US_ASCII);
    private record Header(int formatVersion,int iterations,int chunkSize,String salt,String noncePrefix) { }
    static OutputStream encrypt(OutputStream destination,char[] password) throws Exception {
        requirePassword(password);byte[] salt=random(16),prefix=random(4);
        byte[] header=new ObjectMapper().writeValueAsBytes(new Header(1,ITERATIONS,CHUNK,Base64.getEncoder().encodeToString(salt),Base64.getEncoder().encodeToString(prefix)));
        var output=new DataOutputStream(destination);output.write(MAGIC);output.writeInt(header.length);output.write(header);
        return new Records(output,key(password,salt,ITERATIONS),prefix,MessageDigest.getInstance("SHA-256").digest(header));
    }
    static void decrypt(InputStream source,OutputStream destination,char[] password) throws Exception {
        requirePassword(password);var input=new DataInputStream(source);
        if(!Arrays.equals(input.readNBytes(MAGIC.length),MAGIC))throw new IOException("BACKUP_FORMAT_INVALID");
        int length=input.readInt();if(length<1 || length>1024)throw new IOException("BACKUP_FORMAT_INVALID");
        byte[] encoded=input.readNBytes(length);if(encoded.length!=length)throw new IOException("BACKUP_TRUNCATED");
        var header=new ObjectMapper().readValue(encoded,Header.class);
        if(header.formatVersion()!=1 || header.chunkSize()!=CHUNK || header.iterations()<ITERATIONS || header.iterations()>2_000_000)throw new IOException("BACKUP_FORMAT_INVALID");
        byte[] salt=Base64.getDecoder().decode(header.salt()),prefix=Base64.getDecoder().decode(header.noncePrefix());
        if(salt.length!=16 || prefix.length!=4)throw new IOException("BACKUP_FORMAT_INVALID");
        var key=key(password,salt,header.iterations());byte[] digest=MessageDigest.getInstance("SHA-256").digest(encoded);long sequence=0;
        for(;;){
            int size=input.readInt();long index=input.readLong();
            if(size<0 || size>CHUNK || index!=sequence++)throw new IOException("BACKUP_RECORD_INVALID");
            byte[] ciphertext=input.readNBytes(size+16);if(ciphertext.length!=size+16)throw new IOException("BACKUP_TRUNCATED");
            byte[] plaintext=cipher(Cipher.DECRYPT_MODE,key,prefix,index,digest,size==0).doFinal(ciphertext);
            if(size==0){if(input.read()!=-1)throw new IOException("BACKUP_TRAILING_DATA");return;}
            destination.write(plaintext);Arrays.fill(plaintext,(byte)0);
        }
    }
    static void requirePassword(char[] password){if(password==null || password.length<12 || password.length>1024)throw new IllegalArgumentException("备份口令须为 12–1024 个字符");}
    private static SecretKeySpec key(char[] password,byte[] salt,int iterations) throws Exception {
        var specification=new PBEKeySpec(password,salt,iterations,256);
        try{return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded(),"AES");}
        finally{specification.clearPassword();}
    }
    private static Cipher cipher(int mode,SecretKeySpec key,byte[] prefix,long sequence,byte[] headerHash,boolean last) throws Exception {
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,key,new GCMParameterSpec(128,ByteBuffer.allocate(12).put(prefix).putLong(sequence).array()));
        cipher.updateAAD(ByteBuffer.allocate(41).put(headerHash).putLong(sequence).put((byte)(last?1:0)).array());return cipher;
    }
    private static byte[] random(int size){byte[] bytes=new byte[size];new SecureRandom().nextBytes(bytes);return bytes;}
    private static final class Records extends OutputStream {
        private final DataOutputStream output;private final SecretKeySpec key;private final byte[] prefix,header,buffer=new byte[CHUNK];private int size;private long sequence;private boolean closed;
        Records(DataOutputStream output,SecretKeySpec key,byte[] prefix,byte[] header){this.output=output;this.key=key;this.prefix=prefix;this.header=header;}
        @Override public void write(int value) throws IOException {write(new byte[]{(byte)value},0,1);}
        @Override public void write(byte[] bytes,int offset,int length) throws IOException {
            if(closed)throw new IOException("Backup stream closed");
            while(length>0){int count=Math.min(length,CHUNK-size);System.arraycopy(bytes,offset,buffer,size,count);size+=count;offset+=count;length-=count;if(size==CHUNK)publish(false);}
        }
        private void publish(boolean last) throws IOException {
            try{byte[] encrypted=cipher(Cipher.ENCRYPT_MODE,key,prefix,sequence,header,last).doFinal(buffer,0,size);output.writeInt(size);output.writeLong(sequence++);output.write(encrypted);Arrays.fill(buffer,0,size,(byte)0);size=0;}
            catch(GeneralSecurityException failure){throw new IOException("BACKUP_ENCRYPTION_FAILED",failure);}
            catch(Exception failure){throw new IOException("BACKUP_ENCRYPTION_FAILED",failure);}
        }
        @Override public void close() throws IOException {if(closed)return;if(size>0)publish(false);publish(true);closed=true;output.close();}
    }
}
