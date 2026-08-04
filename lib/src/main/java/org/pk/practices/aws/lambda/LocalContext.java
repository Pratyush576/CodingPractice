package org.pk.practices.aws.lambda;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

import java.util.UUID;

/**
 * Real Lambda never gives you a way to construct a {@link Context} yourself
 * — it's handed to your handler by the runtime on every invoke. Testing a
 * handler outside that runtime (no SAM, no RIE, no deployment) means
 * supplying a stand-in yourself; this is the minimal one, with plausible
 * fake values instead of a real request/log group/ARN.
 */
public class LocalContext implements Context {

    private final String awsRequestId = UUID.randomUUID().toString();

    @Override
    public String getAwsRequestId() {
        return awsRequestId;
    }

    @Override
    public String getLogGroupName() {
        return "/aws/lambda/local-practice";
    }

    @Override
    public String getLogStreamName() {
        return "local-stream";
    }

    @Override
    public String getFunctionName() {
        return "local-practice-function";
    }

    @Override
    public String getFunctionVersion() {
        return "$LATEST";
    }

    @Override
    public String getInvokedFunctionArn() {
        return "arn:aws:lambda:local:000000000000:function:local-practice-function";
    }

    @Override
    public CognitoIdentity getIdentity() {
        return null; // no Cognito-authenticated caller in local practice
    }

    @Override
    public ClientContext getClientContext() {
        return null; // only populated for mobile SDK invokes — irrelevant here
    }

    @Override
    public int getRemainingTimeInMillis() {
        return 30_000; // pretend a fresh 30s Lambda timeout budget
    }

    @Override
    public int getMemoryLimitInMB() {
        return 512;
    }

    @Override
    public LambdaLogger getLogger() {
        // Written as an explicit anonymous class, not a lambda — LambdaLogger declares both
        // log(String) and log(byte[]), so it isn't reliably a single-abstract-method interface
        // across aws-lambda-java-core versions.
        return new LambdaLogger() {
            @Override
            public void log(String message) {
                System.out.println("[LOCAL-LAMBDA-LOG] " + message);
            }

            @Override
            public void log(byte[] message) {
                System.out.println("[LOCAL-LAMBDA-LOG] " + new String(message));
            }
        };
    }
}
