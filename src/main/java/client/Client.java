package client;

import client.service.http.HTTPService;

/**
 * Class Client
 * HTTP客户端的启动类
 */
public class Client {
    /**
     * 一个客户端只会启动一个http服务，运行在一个端口上
     */
    private static HTTPService httpService;

    public static void main(String[] args) {
        httpService = HTTPService.getInstance();
        httpService.init();
        try {
            httpService.run();
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }

}