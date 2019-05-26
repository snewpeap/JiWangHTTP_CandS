package client.service.http;

import http.message.HTTPMessage;
import http.method.HTTPMethod;

public class RequestMessage extends HTTPMessage {
    private HTTPMethod method;
    private String resource;

    private RequestMessage() {

    }

    public RequestMessage(HTTPMethod method, String resource) {
        super();
        this.method = method;
        this.resource = resource;
    }

    @Override
    public String toString() {
        StringBuilder messageBuilder = new StringBuilder();

        //构造Request-Line
        //Format = Method Request-URI HTTP-Version CRLF
        messageBuilder
                .append(method.getMethodName()).append(" ")
                .append(resource).append(" ")
                .append(HTTP_VERSION)
                .append(CRLF);

        return buildContent(buildHeader(messageBuilder)).toString();
    }

    public HTTPMethod getMethod() {
        return method;
    }

    public String getResource() {
        return resource;
    }
}
