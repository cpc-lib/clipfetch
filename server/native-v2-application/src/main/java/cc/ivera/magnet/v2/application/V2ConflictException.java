package cc.ivera.magnet.v2.application;

import cc.ivera.magnet.v2.domain.common.ErrorCodes;
import cc.ivera.magnet.v2.domain.common.V2DomainException;

/** 并发或状态冲突；API 层映射为 409。 */
public class V2ConflictException extends V2DomainException {

    public V2ConflictException(String message) {
        super(ErrorCodes.CONFLICT, message);
    }
}
