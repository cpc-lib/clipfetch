package cc.ivera.magnet.v2.infrastructure.repository;

import cc.ivera.magnet.v2.application.port.NativeV2UnitOfWork;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SpringNativeV2UnitOfWork implements NativeV2UnitOfWork {

    private final TransactionTemplate transactionTemplate;

    public SpringNativeV2UnitOfWork(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(Runnable work) {
        transactionTemplate.executeWithoutResult(status -> work.run());
    }
}
