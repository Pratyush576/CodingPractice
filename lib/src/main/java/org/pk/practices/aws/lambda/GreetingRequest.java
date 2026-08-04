package org.pk.practices.aws.lambda;

/**
 * The classic "hello world" Lambda shape: a plain POJO Lambda deserializes
 * the invoke event JSON into directly (Jackson, wired in by the Lambda
 * runtime itself — not something a handler configures). No AWS-specific
 * base class needed; any bean-shaped POJO works.
 */
public class GreetingRequest {
    private String name;

    public GreetingRequest() {
    }

    public GreetingRequest(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
