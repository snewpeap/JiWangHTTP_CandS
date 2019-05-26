package server.service.http;

import http.message.HTTPMessage;

public class ResponseMessage extends HTTPMessage {
    private ResponseStatus response_status;

    private ResponseMessage() {
    }

    public ResponseMessage(int status_code) {
        super();
        this.response_status = ResponseStatus.getStatusByCode(status_code);
    }

    @Override
    public String toString() {
        StringBuilder messageBuilder = new StringBuilder();

        //构建Status-Line
        //Format = HTTP-Version Status-Code Reason-Phrase CRLF
        messageBuilder
                .append(HTTP_VERSION).append(" ")
                .append(response_status.getStatus_code()).append(" ")
                .append(response_status.getReason_phrase())
                .append(CRLF);

        return buildContent(buildHeader(messageBuilder)).toString();
    }

    enum ResponseStatus {
        STATUS_200(200, "OK"),
        STATUS_301(301, "Moved Permanently"),
        STATUS_302(302, "Found"),
        STATUS_304(304, "Not Modified"),
        STATUS_404(404, "Not Found"),
        STATUS_405(405, "Method Not Allowed"),
        STATUS_500(500, "Internal Server Error");

        private final int status_code;

        public int getStatus_code() {
            return status_code;
        }

        public String getReason_phrase() {
            return reason_phrase;
        }

        private final String reason_phrase;

        ResponseStatus(int status_code, String reason_phrase) {
            this.status_code = status_code;
            this.reason_phrase = reason_phrase;
        }

        static ResponseStatus getStatusByCode(int status_code) {
            for (ResponseStatus responseStatus : ResponseStatus.values()) {
                if (responseStatus.status_code == status_code) {
                    return responseStatus;
                }
            }
            return STATUS_500;
        }
    }
}
