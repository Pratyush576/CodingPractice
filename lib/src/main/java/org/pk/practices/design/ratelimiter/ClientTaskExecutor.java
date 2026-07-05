package org.pk.practices.design.ratelimiter;

import org.pk.practices.design.ratelimiter.impl.TokenBucketRateLimiter;

import java.net.UnknownServiceException;

public class ClientTaskExecutor {
    Clients client;
    TokenBucketRateLimiter tokenBucketRateLimiter;

    ClientTaskExecutor(Clients client, TokenBucketRateLimiter tokenBucketRateLimiter) {
        this.client = client;
        this.tokenBucketRateLimiter = tokenBucketRateLimiter;
    }
    public void execute(long sleeptime) {
        for (int i = 0; i < 100 ; i++) {
            try {
                tokenBucketRateLimiter.isUnderLimit(client);
            } catch (UnknownServiceException e) {
                throw new RuntimeException(e);
            }

            try {
                Thread.sleep(sleeptime);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
