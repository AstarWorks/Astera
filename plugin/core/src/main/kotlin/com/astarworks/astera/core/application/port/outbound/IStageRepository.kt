package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.port.outbound.Repository
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId

public interface IStageRepository : Repository<StageId, Stage>
