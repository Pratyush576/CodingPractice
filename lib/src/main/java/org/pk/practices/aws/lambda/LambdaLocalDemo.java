package org.pk.practices.aws.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.util.Map;

/**
 * Exercises both handlers exactly the way the Lambda runtime itself would —
 * construct the event POJO, hand it and a {@link Context} to
 * {@code handleRequest()} — just without any of SAM, Docker, or a real AWS
 * account involved. Nothing here is Lambda infrastructure; it's a plain
 * Java {@code main()} standing in for what the runtime normally does.
 */
public class LambdaLocalDemo {

    public static void main(String[] args) {
        Context context = new LocalContext();

        System.out.println("=== GreetingHandler (plain POJO in/out) ===");
        GreetingHandler greetingHandler = new GreetingHandler();
        GreetingResponse response = greetingHandler.handleRequest(new GreetingRequest("Pratyush"), context);
        System.out.println("Response: " + response);

        GreetingResponse defaultResponse = greetingHandler.handleRequest(new GreetingRequest(null), context);
        System.out.println("Response (no name given): " + defaultResponse);

        System.out.println();
        System.out.println("=== ApiGatewayHandler (API Gateway proxy integration shape) ===");
        ApiGatewayHandler apiGatewayHandler = new ApiGatewayHandler();

        APIGatewayProxyRequestEvent helloRequest = new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withPath("/hello")
                .withQueryStringParameters(Map.of("name", "Pratyush"));
        APIGatewayProxyResponseEvent helloResponse = apiGatewayHandler.handleRequest(helloRequest, context);
        System.out.println("GET /hello?name=Pratyush -> " + helloResponse.getStatusCode() + " " + helloResponse.getBody());

        APIGatewayProxyRequestEvent unknownRequest = new APIGatewayProxyRequestEvent()
                .withHttpMethod("GET")
                .withPath("/does-not-exist");
        APIGatewayProxyResponseEvent unknownResponse = apiGatewayHandler.handleRequest(unknownRequest, context);
        System.out.println("GET /does-not-exist -> " + unknownResponse.getStatusCode() + " " + unknownResponse.getBody());
    }
}
