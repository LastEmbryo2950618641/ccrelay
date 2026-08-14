package com.webank.wedatasphere.wdsavs.aiagent.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CenterSshAccessServiceImplTest {

    @Test
    void autoUsesRsaForCompatibilityWhenCreatingCenterKey() {
        CenterSshAccessServiceImpl service = new CenterSshAccessServiceImpl("AUTO");

        assertEquals("RSA", service.keyAlgorithm());
        assertEquals(List.of("id_rsa"), service.keyNames());
        assertEquals(List.of("-q", "-t", "rsa", "-b", "3072"), service.keygenArguments());
    }

    @Test
    void explicitEd25519PrioritizesExistingEd25519Key() {
        CenterSshAccessServiceImpl service = new CenterSshAccessServiceImpl("ED25519");

        assertEquals("ED25519", service.keyAlgorithm());
        assertEquals(List.of("id_ed25519"), service.keyNames());
        assertEquals(List.of("-q", "-t", "ed25519"), service.keygenArguments());
    }
}
