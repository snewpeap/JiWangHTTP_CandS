package http.mime;

public enum MimeType {
    IMAGE_JPEG("image/jpeg", "jpeg"),
    IMAGE_PNG("image/png", "png"),
    TEXT_PLAIN("text/plain", "txt"),
    TEXT_HTML("text/html", "html");

    private final String typeString;
    private final String postfix;

    MimeType(String typeString, String postfix) {
        this.typeString = typeString;
        this.postfix = postfix;
    }

    public String getTypeString() {
        return typeString;
    }

    public static String getPostfix(String mimeType) {
        String postfix = "txt";
        for (MimeType type : MimeType.values()) {
            if (type.typeString.equals(mimeType)) {
                postfix = type.postfix;
            }
        }
        return postfix;
    }
}
