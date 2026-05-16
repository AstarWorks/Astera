package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.application.port.outbound.Repository
import com.astarworks.astera.match.domain.stage.Stage
import com.astarworks.astera.match.domain.stage.StageId

public interface IStageRepository : Repository<StageId, Stage>
