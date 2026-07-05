package org.pk.practices.design.ratelimiter.impl;

import org.pk.practices.design.ratelimiter.Clients;
import org.pk.practices.design.ratelimiter.RateLimiter;
import org.pk.practices.design.ratelimiter.config.tokenbucket.ClientAClientConfig;
import org.pk.practices.design.ratelimiter.config.tokenbucket.ClientBClientConfig;
import org.pk.practices.design.ratelimiter.config.tokenbucket.ClientCClientConfig;
import org.pk.practices.design.ratelimiter.config.tokenbucket.TokenBucketClientConfig;

import java.net.UnknownServiceException;

public class TokenBucketRateLimiter implements RateLimiter {

    public static final int TOKEN_COUNT = 5; //number of allowed token
    public static final long WINDOW_SIZE = 20000; //milliseconds
    TokenBucketClientConfig clientAConfig = new ClientAClientConfig();
    TokenBucketClientConfig clientBConfig = new ClientBClientConfig();
    TokenBucketClientConfig clientCConfig = new ClientCClientConfig();

    public TokenBucketRateLimiter() {
        refill(clientAConfig);
        refill(clientBConfig);
        refill(clientCConfig);
    }

    @Override
    public boolean isUnderLimit(Clients client) throws UnknownServiceException {
        TokenBucketClientConfig clientConfig = getClientConfig(client);
        refill(clientConfig);
        if (clientConfig.currentTokenCount > 0) {
            clientConfig.currentTokenCount--;
            System.out.println(clientConfig + " Client request was accepted");
            return true;
        }

        System.out.println(clientConfig + " Client request was denied");
        return false;
    }

    private void refill(TokenBucketClientConfig clientConfig) {
        // refill the bucked if bucket requires a refill
        // reset the refill time
        long currentTime = System.currentTimeMillis();
        if(currentTime > clientConfig.lastFilledTime + WINDOW_SIZE) {
            clientConfig.currentTokenCount += TOKEN_COUNT;
            clientConfig.lastFilledTime = currentTime;
        }
    }

    private TokenBucketClientConfig getClientConfig(Clients client) throws UnknownServiceException {
        switch (client) {
            case CLIENT_A -> {
                return clientAConfig;
            }
            case CLIENT_B -> {
                return clientBConfig;
            }
            case CLIENT_C -> {
                return clientCConfig;
            }
            default -> {
                throw new UnknownServiceException("The Client is unknown");
            }
        }
    }
}
