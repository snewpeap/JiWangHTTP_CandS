package http.method;

public enum HTTPMethod {
    GET("GET"),
    POST("POST");

    private final String methodName;
    HTTPMethod(String methodName){
        this.methodName = methodName;
    }
    public String getMethodName(){
        return this.methodName;
    }

}
