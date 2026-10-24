package dev.handsup.common.redisson;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;


@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE) // 트랜잭션 어드바이스보다 항상 바깥에서 실행되도록 보장
public class DistributeLockAop {

    private static final String REDISSON_KEY_PREFIX = "RLOCK_";
    private final RedissonClient redissonClient;
    private final AopForTransaction aopForTransaction;

    @Around("@annotation(dev.handsup.common.redisson.DistributeLock)")
    public Object concurrencyLock(final ProceedingJoinPoint joinPoint) throws Throwable {

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        DistributeLock annotation = method.getAnnotation(DistributeLock.class);

        final long waitTime = annotation.waitTime();
        final long leaseTime = annotation.leaseTime();
        final TimeUnit unit = annotation.timeUnit();
        final String lockName = annotation.key();

        String key = REDISSON_KEY_PREFIX + CustomSpringELParser.getDynamicValue(signature.getParameterNames(),
            joinPoint.getArgs(), lockName);
        RLock rLock = redissonClient.getLock(key);

        try {
            boolean hasLock = rLock.tryLock(waitTime, leaseTime, unit);

            if (!hasLock) {
                throw new LockAcquisitionException();
            }

            log.info("get lock success {}", key);
            // 실제 수행 로직
            return aopForTransaction.proceed(joinPoint);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("get lock fail {}", key);
            throw new InterruptedException();
        } finally {
            if (rLock.isHeldByCurrentThread()) {
                rLock.unlock();
                log.info("lock released {}", key);
            } else {
                log.warn("unlock skipped: lock not held by current thread. key={}", key);
            }
        }
    }
}
