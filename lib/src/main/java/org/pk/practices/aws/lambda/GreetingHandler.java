package org.pk.practices.aws.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;

/**
 * The simplest possible real Lambda: implement {@link RequestHandler}, name
 * the fully-qualified class as the deployed function's handler string
 * ({@code org.pk.practices.aws.lambda.GreetingHandler::handleRequest}), and
 * the Lambda runtime does the rest — deserializes the invoke event into
 * {@link GreetingRequest}, calls this method, serializes whatever comes back.
 *
 * <p>Nothing here is Lambda-specific except the interface and the
 * {@link Context} parameter — this is exactly the "just write a function"
 * pitch of serverless. {@link Context} is what carries the
 * request-scoped metadata (remaining time, request ID, the logger that
 * writes to CloudWatch) a handler wouldn't otherwise have access to.
 */
public class GreetingHandler implements RequestHandler<GreetingRequest, GreetingResponse> {

    @Override
    public GreetingResponse handleRequest(GreetingRequest request, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("Invocation " + context.getAwsRequestId() + " — greeting '" + request.getName() + "'");

        String name = (request.getName() == null || request.getName().isBlank()) ? "world" : request.getName();
        return new GreetingResponse("Hello, " + name + "!");
    }
}
