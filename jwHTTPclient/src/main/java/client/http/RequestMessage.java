package client.http;

import http.message.HTTPMessage;
import http.method.HTTPMethod;

class RequestMessage extends HTTPMessage {
    private HTTPMethod method;
    private String resource;

    static {
        set_property_split_ignore_case.add("If-Modified-Since".toLowerCase());
    }

    private RequestMessage() {
        super();
    }

    RequestMessage(HTTPMethod method, String resource) {
        this();
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
