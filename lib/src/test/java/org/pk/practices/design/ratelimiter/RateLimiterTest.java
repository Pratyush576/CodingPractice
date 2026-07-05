package org.pk.practices.design.ratelimiter;

import org.pk.practices.design.ratelimiter.impl.TokenBucketRateLimiter;

public class RateLimiterTest {
    TokenBucketRateLimiter tokenBucketRateLimiter = new TokenBucketRateLimiter();
    ClientTaskExecutor aExec = new ClientTaskExecutor(Clients.CLIENT_A, tokenBucketRateLimiter);
    ClientTaskExecutor bExec = new ClientTaskExecutor(Clients.CLIENT_B, tokenBucketRateLimiter);
    ClientTaskExecutor cExec = new ClientTaskExecutor(Clients.CLIENT_C, tokenBucketRateLimiter);

    Thread clientA = new Thread(() -> aExec.execute(1000), "CLIENTA");
    Thread clientB = new Thread(() -> bExec.execute(2000), "CLIENTB");
    Thread clientC = new Thread(() -> cExec.execute(500), "CLIENTC");


    public void test() {
        clientA.start();
        clientB.start();
        clientC.start();
    }

    public static void main(String[] args) {
        RateLimiterTest tester = new RateLimiterTest();
        tester.test();
    }
}
