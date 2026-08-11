package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CenterSshPublicKeyView {
    private String algorithm;
    private String publicKey;
    private String fingerprint;
}
