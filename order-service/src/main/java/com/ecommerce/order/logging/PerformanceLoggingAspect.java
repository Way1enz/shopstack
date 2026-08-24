package com.ecommerce.order.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Wraps @LogPerformance methods and logs how long they took, success or failure.
// Woven in via the same Spring AOP proxy mechanism Resilience4j's annotations already use.
@Aspect
@Component
public class PerformanceLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(PerformanceLoggingAspect.class);

    @Around("@annotation(com.ecommerce.order.logging.LogPerformance)")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        String signature = joinPoint.getSignature().toShortString();
        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            log.info("{} completed in {}ms", signature, System.currentTimeMillis() - start);
            return result;
        } catch (Throwable ex) {
            log.warn("{} failed after {}ms: {}", signature, System.currentTimeMillis() - start, ex.getMessage());
            throw ex;
        }
    }
}
