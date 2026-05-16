package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.model.stage.Stage
import com.astarworks.astera.domain.model.stage.StageId

public interface IStageRepository : Repository<StageId, Stage>
