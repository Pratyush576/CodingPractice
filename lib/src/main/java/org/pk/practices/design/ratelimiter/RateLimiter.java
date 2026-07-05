package org.pk.practices.design.ratelimiter;

import java.net.UnknownServiceException;

public interface RateLimiter {
     boolean isUnderLimit(Clients client) throws UnknownServiceException;

}
