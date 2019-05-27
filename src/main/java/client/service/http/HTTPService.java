package client.service.http;

import http.message.HTTPMessage;
import http.method.HTTPMethod;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端的HTTP服务类，负责收发HTTP消息，处理接收到的HTTP消息中的内容，消息均以字符串的方式发送和接收
 * 服务通过命令行接受用户命令，目前已实现的命令有：
 * Send [direct|interact (default)]：发送HTTP请求
 * |
 * |-- direct：通过直接输入URL的方式发送HTTP请求，默认使用GET方法（直接输入URL通常也是使用GET方法）
 * |---- i.e. http://127.0.0.1:8089/index.html
 * |
 * |-- interact：通过交互式的输入方式发送HTTP请求，可以指定host(主机),port(端口),method(方法),resource(资源路径)
 * o
 */
public class HTTPService {
    //标志服务是否处于运行状态
    private static boolean isActive;

    //用于在命令行中展示消息时的前缀
    private static final String prefix = "Http";

    //用于解析命令的Map
    private static final Map<String, CommandHandler> handlerMap = new HashMap<>();

    //用于保存连接SocketChannel的Map，复用open过的SocketChannel
    private static Map<String, SocketChannel> httpMap;

    //用于保存接收到301响应后的跳转
    private static Map<String, String> redirectMap;

    //用于保存文件的Last-Modified信息
    private static Map<String, String> lastModifiedMap;

    //维护一个HTTP服务对象，以实现单例
    private static HTTPService httpService;

    static {
        handlerMap.put("send", Send_Handler.getInstance());
    }

    private HTTPService() {
    }

    public static HTTPService getInstance() {
        synchronized (HTTPService.class) {
            if (httpService == null) {
                httpService = new HTTPService();
            }
            return httpService;
        }
    }

    /**
     * 普通的消息提示
     *
     * @param s 输出的消息
     */
    private static void notify(String s) {
        System.out.println(prefix + " : " + s);
    }

    /**
     * 等待输入的前缀
     */
    private static void waitForInput() {
        System.out.print(prefix + " > ");
    }

    /**
     * err级别的消息提示
     *
     * @param s 输出的错误消息
     */
    private static void err(String s) {
        System.err.println(prefix + " : " + s);
    }

    /**
     * 无参的初始化方法
     */
    public synchronized void init() {
        if (isActive) {
            notify("HTTP service has already been initialized.");
            return;
        }
        isActive = true;
        httpMap = new ConcurrentHashMap<>();
        redirectMap = new ConcurrentHashMap<>();
        lastModifiedMap = new ConcurrentHashMap<>();
        notify("HTTP service is successfully initialized.");
    }

    /**
     * 无参的运行方法，在该方法内接受用户的指令，并调用对应的CommandHandler来处理指令
     *
     * @throws Exception 服务未初始化
     */
    public void run() throws Exception {
        if (!isActive) throw new Exception("Service Not Yet initialized.");
        notify("HTTP service is running.");

        //接受用户输入
        Scanner sc = new Scanner(System.in);
        String next;
        waitForInput();
        while (!(next = sc.nextLine()).equalsIgnoreCase("quit")) {
            try {
                String[] inputs = next.split(" ");
                handlerMap.get(inputs[0].toLowerCase())
                        .execute(
                                inputs.length == 1 ? null : Arrays.copyOfRange(inputs, 1, inputs.length)
                        );
            } catch (NullPointerException npe) {
                err("No such Command!");
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                waitForInput();
            }
        }

        //用户输入quit退出服务后的行为
        //逐一关闭SocketChannel
        Iterator<Map.Entry<String, SocketChannel>> iterator = httpMap.entrySet().iterator();
        while (iterator.hasNext()) {
            SocketChannel channelToClose = iterator.next().getValue();
            try {
                channelToClose.close();
                channelToClose.socket().close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            iterator.remove();
        }
        notify("Bye bye.");
    }

    /**
     * Inner Interface CommandHandler
     * 处理指令的handler内部接口类
     * execute方法为执行指令的有参方法
     */
    private interface CommandHandler {
        void execute(String[] args) throws Exception;
    }

    /**
     * Inner Class Send_Handler
     * 处理send指令的handler内部类
     */
    private static class Send_Handler implements CommandHandler {
        private static Send_Handler send_handler;

        /**
         * 获得SendHandler单例
         *
         * @return SendHandler单例
         */
        static Send_Handler getInstance() {
            if (send_handler == null) {
                send_handler = new Send_Handler();
            }
            return send_handler;
        }

        /**
         * 执行指令的有参方法，方法内接受用户输入并调用ConnectionHolder的请求方法
         *
         * @param args send指令参数，可以为direct或interact，为null时默认为interact
         */
        @Override
        public void execute(String[] args) {
            if (args == null) {
                args = new String[]{"interact"};
            }
            List<String> argsList = new ArrayList<>(Arrays.asList(args));
            ConnectionHolder ch = ConnectionHolder.getInstance();
            Scanner sc = new Scanner(System.in);

            if (argsList.contains("direct")) {
                String rawURL;
                if ((rawURL = sc.nextLine().trim()).isEmpty()) { //输入为空，什么也不做
                    return;
                }
                if (!rawURL.toLowerCase().startsWith("http://")) { //补全http://到url的头部
                    rawURL = "http://" + rawURL;
                }

                //截取出URL中指定了主机和端口的部分来解析，如127.0.0.1:8089
                String addressWindow = rawURL.substring("http://".length(), rawURL.indexOf('/', "http://".length()));
                if (addressWindow.contains(":")) { //含有':'说明在URL中显示指明了端口号
                    String[] ss = addressWindow.split(":");
                    try {
                        ch.host = ss[0].toLowerCase();
                        ch.port = Integer.parseInt(ss[ss.length - 1]);
                    } catch (NumberFormatException nfe) {
                        nfe.printStackTrace();
                        return;
                    }
                } else { //没有':'说明该字段内只含有主机地址
                    ch.host = addressWindow.toLowerCase();
                    //端口号采用默认的端口号，在本项目中为8089，声明于ConnectionHolder中
                }
                ch.resource = rawURL.substring(rawURL.indexOf('/', "http://".length())).toLowerCase();
            } else if (argsList.contains("interact")) {
                String host = ch.host;//默认为"127.0.0.1"
                String portString;//默认为8089
                String resource = ch.resource;//默认为"/"
                String methodString;//默认为HTTPMethod.GET

                //进行交互式输入
                System.out.print("Host [ " + ch.host + " ] : ");
                if ((ch.host = sc.nextLine().trim().toLowerCase()).isEmpty()) {
                    ch.host = host;
                }

                //输入端口
                System.out.print("Port [ " + ch.port + " ] : ");
                if (!(portString = sc.nextLine().trim()).isEmpty()) {
                    try {
                        ch.port = Integer.parseInt(portString);
                    } catch (NumberFormatException nfe) {
                        err("Port not a number.");
                        return;
                    }
                }

                //输入资源路径
                System.out.print("Resource [ " + ch.resource + " ] : ");
                if ((ch.resource = sc.nextLine().trim().toLowerCase()).isEmpty()) {
                    ch.resource = resource;
                }

                //输入方法，GET或POST
                System.out.print("Method [ " + ch.method + " ] : ");
                if (!(methodString = sc.nextLine().trim()).isEmpty()) {
                    if (methodString.equalsIgnoreCase("POST") || methodString.equalsIgnoreCase("GET")) {
                        ch.method = HTTPMethod.valueOf(methodString.toUpperCase());//通过名字获取HTTPMethod
                    } else {
                        System.out.println("Method has not been supported");
                        return;
                    }
                }

                if (ch.method == HTTPMethod.POST) {
                    System.out.println("Available type:\n1. text;\n2. image");
                    System.out.print("Content-type [ " + ch.content_type + " ] : ");
                    int type_option = Integer.parseInt(sc.nextLine());

                    switch (type_option) {
                        case 1:
                            System.out.print("Text : ");
                            ch.content = sc.nextLine().getBytes(StandardCharsets.UTF_8);
                            ch.content_type = "text/plain";
                            break;
                        case 2:
                            String filePath;
                            System.out.print("FilePath : ");
                            if (!(filePath = sc.nextLine()).isEmpty()) {
                                Path file = Paths.get(filePath);
                                if (!Files.exists(file)) {
                                    System.out.println("No such image.");
                                    return;
                                } else if (Files.isDirectory(file)) {
                                    System.out.println("Directory is not allowed");
                                    return;
                                } else {
                                    try {
                                        String realType = Files.probeContentType(file);
                                        if (!realType.startsWith("image")) {
                                            System.out.println("File is not image");
                                            return;
                                        }
                                        ch.content_type = realType;
                                        ch.content = Base64.getMimeEncoder().encode(Files.readAllBytes(file));
                                    } catch (IOException ioe) {
                                        ioe.printStackTrace();
                                        return;
                                    }
                                }
                            }
                            break;
                        default:
                            System.out.println("Content-type has not been supported.");
                            return;
                    }
                }
            }

            //交由ConnectionHolder处理具体请求
            try {
                ch.handleRequest();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Inner Class ConnectionHolder
     * 实际管理连接服务器、请求和响应的内部类
     * 每一个ConnectionHolder只应该负责一次请求，若有需要重新请求的，需new一个ConnectionHolder来处理
     */
    private static class ConnectionHolder {
        String host = "127.0.0.1";
        int port = 8089;
        HTTPMethod method = HTTPMethod.GET;
        String resource = "/";
        String content_type = "text";
        byte[] content = null;

        ConnectionHolder() {
        }

        ConnectionHolder(String host, int port, HTTPMethod method, String resource) {
            this.host = host;
            this.port = port;
            this.method = method;
            this.resource = resource;
        }

        /**
         * 此处并非单例，而是由于CommandHandler是静态类，其中的静态方法不能指定到最外层HTTPService对象的this
         *
         * @return 新的ConnectionHolder对象
         */
        static ConnectionHolder getInstance() {
            return new ConnectionHolder();
        }

        /**
         * 建立通信、发起请求的无参方法
         *
         * @throws Exception SocketChannel和handleResponse方法的异常
         */
        void handleRequest() throws Exception {
            //301重定向
            String noPrefixURL = host + ":" + port;
            if (redirectMap.containsKey(noPrefixURL + resource)) {
                resource = getResource(redirectMap.get(noPrefixURL + resource));
            }

            //构建请求报文
            RequestMessage req = new RequestMessage(method, resource);
            req.setProperty("Host", host);
            if (method == HTTPMethod.POST) {
                req.setProperty("Content-Type", content_type);
            }
            if (lastModifiedMap.containsKey(resource)) {
                req.setProperty("If-Modified-Since", lastModifiedMap.get(resource));
            }
            req.setContent(content);

            //建立与Http服务器的通信
            SocketChannel socketChannel;
            if (!httpMap.containsKey(noPrefixURL)) {
                socketChannel = SocketChannel.open();
                socketChannel.configureBlocking(false);
                socketChannel.connect(new InetSocketAddress(host, port));
                httpMap.put(noPrefixURL, socketChannel);
            } else {
                socketChannel = httpMap.get(noPrefixURL);
                // 服务端主动断掉连接时，客户端需要用此方法来判断连接是否已经断开
                // 服务端会在其输出流中写入-1，以此来判断
                if (socketChannel.read(ByteBuffer.allocate(8)) < 0) {
                    socketChannel = SocketChannel.open();
                    socketChannel.configureBlocking(false);
                    socketChannel.connect(new InetSocketAddress(host, port));
                    httpMap.replace(noPrefixURL, socketChannel);
                }
            }
            try {
                do {
                    //阻塞到连接完成
                    socketChannel.finishConnect();
                } while (!socketChannel.finishConnect());
                HTTPService.notify("Connect to Http server.");

                //发送请求报文
                socketChannel.write(ByteBuffer.wrap(req.toString().getBytes()));
                HTTPService.notify("Request to Http server.\n>>>>>>>>>>>>>>>>>>>>");
                System.out.println(req.toString() + "\n>>>>>>>>>>>>>>>>>>>>");

                //接受响应报文
                ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
                StringBuilder stringBuilder = new StringBuilder();
                int count;
                String res;
                do {
                    //阻塞至有读入
                    byteBuffer.clear();
                } while ((count = socketChannel.read(byteBuffer)) <= 0);
                do {
                    stringBuilder.append(new String(byteBuffer.array(), 0, count));
                    byteBuffer.clear();
                } while ((count = socketChannel.read(byteBuffer)) > 0);

                res = stringBuilder.toString();
                handleResponse(res);

                //关闭连接，模拟Http无连接
                /*socketChannel.close();
                socketChannel.socket().close();
                HTTPService.notify("Disconnect from Http Server");*/
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * 处理响应的无参方法
         *
         * @param res 响应字符串
         * @throws Exception 调用handleRequest方法抛出的异常
         */
        void handleResponse(String res) throws Exception {
            //TODO: 历史记录
            HTTPService.notify("Response from Http Server\n<<<<<<<<<<<<<<<<<<<<");
            System.out.println(res + "\n<<<<<<<<<<<<<<<<<<<<");

            final String CRLF = HTTPMessage.getCRLF();
            //对响应切片
            List<String> headerFields = new ArrayList<>(Arrays.asList(res.substring(0, res.indexOf(CRLF + CRLF)).split(CRLF)));
            List<String> statLineParts = new ArrayList<>(Arrays.asList(headerFields.get(0).split(" ")));
            headerFields.remove(0);

            int statusCode = Integer.parseInt(statLineParts.get(1));
            //String content = res.substring(res.indexOf(CRLF + CRLF) + CRLF.length() * 2);
            String location = "";
            switch (statusCode) {
                case 301:
                    //301重定向
                    for (String field : headerFields) {
                        if (field.startsWith("Location:")) {
                            location = field.split("http://")[1].trim();
                            redirectMap.put(host + ":" + port + resource, getResource(location));
                            break;
                        }
                    }
                    new ConnectionHolder(
                            getHost(location),
                            getPort(location),
                            method,
                            getResource(location)
                    ).handleRequest();
                    break;
                case 302:
                    //302需要跳转
                    for (String field : headerFields) {
                        if (field.startsWith("Location:")) {
                            location = field.split("http://")[1].trim();
                            break;
                        }
                    }
                    new ConnectionHolder(
                            getHost(location),
                            getPort(location),
                            method,
                            getResource(location)
                    ).handleRequest();
                    break;
                case 304:
                    //304服务端资源未修改，从缓存读取
                    HTTPService.notify("Read resource from cache");
                    break;
                case 200:
                    String last_modified = getFiled(headerFields, "last-modified");
                    lastModifiedMap.put(resource, last_modified);
                    break;
                default:
                    //404、500，直接打印
            }
        }

        private String getHost(String location) {
            if (location.contains(":")) {
                return location.split(":")[0];
            } else {
                return location.substring(0, location.indexOf('/'));
            }
        }

        private int getPort(String location) {
            if (location.contains(":")) {
                return Integer.parseInt(location.split("[:/]")[1]);
            } else {
                return port;
            }
        }

        private String getResource(String location) {
            return location.substring(location.indexOf('/'));
        }

        private String getFiled(List<String> headerFields, String key) {
            String field = "";
            for (String s : headerFields) {
                if (s.toLowerCase().startsWith(key.toLowerCase())) {
                    field = s.substring(s.indexOf(':')+1);
                    break;
                }
            }
            return field;
        }
    }
}
