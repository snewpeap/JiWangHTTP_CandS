package server;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;

@Deprecated
public class IOServer {
    /*public static void main(String[] args) {
        IOServer ioServer = new IOServer();
        try {
            ioServer.run();
        } catch (IOException e){
            e.printStackTrace();
        }
    }*/

    public void run() throws IOException{
        ServerSocket serverSocket = new ServerSocket(8088);

        new Thread(() -> {
            while (true){
                try {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> {
                        try {
                            byte[] data = new byte[1024];
                            InputStream inputStream = socket.getInputStream();
                            while (true) {
                                int len;
                                while ((len = inputStream.read(data)) != -1){
                                    System.out.println(new String(data,0,len));
                                }
                            }
                        } catch (IOException e){
                            e.printStackTrace();
                        }
                    }).start();
                } catch (IOException e){
                    e.printStackTrace();
                }
            }
        }).start();
    }
}
