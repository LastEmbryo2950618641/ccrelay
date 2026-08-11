package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationBatchView;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationQuery;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationView;

public interface TaskObservationService {

    TaskObservationView observe(TaskObservationQuery query);

    TaskObservationBatchView observeBatch(TaskObservationQuery query);
}
