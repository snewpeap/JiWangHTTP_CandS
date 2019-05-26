package server;

import server.service.IService;
import server.service.http.ServerHTTPService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Class Server
 * HTTP服务器的启动类
 */
public class Server {
    List<IService> services;

    public Server() {
        System.out.println("Server : Server Starting with out any service...");
    }

    /**
     * Server初始化时，注入需要服务列表，类似于开机启动的应用
     * @param services 需要在Server初始化时启动的服务
     */
    public Server(List<IService> services) {
        System.out.println("Server : Server Starting...");
        this.services = services;
    }

    public static void main(String[] args) {
        List<IService> startUpServices = new ArrayList<>();
        startUpServices.add(new ServerHTTPService(8089));
        Server server = new Server(startUpServices);
        server.run();
    }

    /**
     * Server的无参启动方法，将会遍历已初始化的服务列表，并逐一初始化和运行服务
     */
    private void run() {
        for (IService service : services) {
            System.out.println("Server : Initializing " + service.toString() + "...");
            try {
                service.init();
            } catch (IOException e) {
                System.out.println("Server : Service" + service.toString() + " failed to start.");
            }
            new Thread((Runnable) service).start();
        }
        System.out.println("Server : Server is running.");
    }
}
