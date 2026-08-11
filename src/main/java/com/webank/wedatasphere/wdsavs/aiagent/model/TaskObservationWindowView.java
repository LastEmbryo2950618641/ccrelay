package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class TaskObservationWindowView {
    private Long sinceSequenceNo;
    private Long sinceCreatedTimeMs;
    private Long lastMs;
    private Integer limit;
    private Integer tailLines;
    private Long maxBytes;
    private Long perEventMaxBytes;
    private Integer matchedCount;
    private Integer returnedCount;
    private Boolean truncated = Boolean.FALSE;
}
