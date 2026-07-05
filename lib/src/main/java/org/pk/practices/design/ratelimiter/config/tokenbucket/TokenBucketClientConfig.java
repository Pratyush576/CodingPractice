package org.pk.practices.design.ratelimiter.config.tokenbucket;

public abstract class TokenBucketClientConfig {

    protected static int DEFAULT_TOKEN_COUNT = 10;
    protected static final long WINDOW_SIZE = 60000; //milliseconds

    protected String CLIENT_NAME = "DEFAULT";
    protected static int tokenCount; //number of allowed token
    public int currentTokenCount = 0;
    public long lastFilledTime = 0;

    public String getClientName() {
        return CLIENT_NAME;
    }

    @Override
    public String toString() {
        return CLIENT_NAME + ": currentTokenCount- " + currentTokenCount + ", lastFilledTime - " + lastFilledTime;
    }
}
