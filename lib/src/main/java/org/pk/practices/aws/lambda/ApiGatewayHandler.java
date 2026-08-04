package org.pk.practices.aws.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * The other extremely common shape: a Lambda sitting behind API Gateway's
 * proxy integration. API Gateway hands the ENTIRE HTTP request — method,
 * path, query params, headers, body — as one {@link APIGatewayProxyRequestEvent}
 * POJO (from {@code aws-lambda-java-events}); the handler is responsible for
 * building the whole HTTP response back, status code included. There's no
 * framework routing here on purpose — this is what you get before you reach
 * for one.
 */
public class ApiGatewayHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        context.getLogger().log(request.getHttpMethod() + " " + request.getPath());

        if ("GET".equals(request.getHttpMethod()) && "/hello".equals(request.getPath())) {
            String name = request.getQueryStringParameters() == null
                    ? "world"
                    : request.getQueryStringParameters().getOrDefault("name", "world");
            return jsonResponse(200, Map.of("message", "Hello, " + name + "!"));
        }

        return jsonResponse(404, Map.of("error", "Not found: " + request.getHttpMethod() + " " + request.getPath()));
    }

    private APIGatewayProxyResponseEvent jsonResponse(int statusCode, Object body) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize response body", e);
        }
    }
}
