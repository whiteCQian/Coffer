package com.coffer.config;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
class MinioTlsTest {
    @TempDir Path directory;
    @Test void minioAcceptsTrustedCaAndRejectsUntrustedOrMismatchedServiceIdentity() throws Exception {
        Path store=directory.resolve("server.p12");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","server","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=localhost","-ext","SAN=dns:localhost","-keystore",store.toString(),"-storetype","PKCS12","-storepass","fixturepass123","-keypass","fixturepass123").redirectErrorStream(true).start();
        assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
        var keyStore=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(store)){keyStore.load(in,"fixturepass123".toCharArray());}
        Path ca=directory.resolve("ca.pem"); Files.writeString(ca,"-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(keyStore.getCertificate("server").getEncoded())+"\n-----END CERTIFICATE-----\n");
        var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(keyStore,"fixturepass123".toCharArray());
        var tls=SSLContext.getInstance("TLS");tls.init(keys.getKeyManagers(),null,null);
        var server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/",exchange->{
            byte[] body="<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>".getBytes();
            if(exchange.getRequestMethod().equals("HEAD")){exchange.sendResponseHeaders(200,-1);}else{exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}exchange.close();
        });server.start();
        try {
            var properties=new MinioConfig.MinioProperties();properties.setEndpoint("https://localhost:"+server.getAddress().getPort());properties.setAccessKey("fixture-access");properties.setSecretKey("fixture-secret");properties.setCaCertificate(ca.toString());
            var config=new MinioConfig();
            assertThat(config.minioClient(properties).bucketExists(io.minio.BucketExistsArgs.builder().bucket("fixture-bucket").build())).isTrue();
            properties.setEndpoint("https://127.0.0.1:"+server.getAddress().getPort());
            assertThatThrownBy(()->config.minioClient(properties).bucketExists(io.minio.BucketExistsArgs.builder().bucket("fixture-bucket").build())).isInstanceOf(java.io.IOException.class);
            properties.setEndpoint("https://localhost:"+server.getAddress().getPort());properties.setCaCertificate(null);
            assertThatThrownBy(()->config.minioClient(properties).bucketExists(io.minio.BucketExistsArgs.builder().bucket("fixture-bucket").build())).isInstanceOf(java.io.IOException.class);
        } finally {server.stop(0);}
    }
}
