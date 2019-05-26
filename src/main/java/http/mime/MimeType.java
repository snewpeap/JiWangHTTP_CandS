package http.mime;

public enum MimeType {
    IMAGE_JPEG("image/jpeg", "jpeg"),
    IMAGE_PNG("image/png", "png"),
    TEXT_PLAIN("text/plain", "txt");

    private String mimeType;
    private String postfix;

    MimeType(String mimeType, String postfix) {
        this.mimeType = mimeType;
        this.postfix = postfix;
    }
    public static String getPostfix(String mimeType){
        String postfix = "txt";
        for (MimeType type:MimeType.values()){
            if (type.mimeType.equals(mimeType)){
                postfix = type.postfix;
            }
        }
        return postfix;
    }
}
