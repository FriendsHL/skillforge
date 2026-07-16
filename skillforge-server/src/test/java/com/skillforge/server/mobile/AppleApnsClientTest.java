package com.skillforge.server.mobile;

import org.junit.jupiter.api.Test;
import java.security.*;
import static org.assertj.core.api.Assertions.assertThat;

class AppleApnsClientTest {
    @Test
    void derToJose_convertsEs256SignatureToFixed64Bytes() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair pair = generator.generateKeyPair();
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(pair.getPrivate()); signature.update("payload".getBytes());
        assertThat(AppleApnsClient.derToJose(signature.sign(), 64)).hasSize(64);
    }
}
