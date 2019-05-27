package server.service.http;

import http.message.HTTPMessage;
import http.method.HTTPMethod;
import http.mime.MimeType;
import server.service.IService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端的HTTP服务类，负责收发HTTP消息，处理接收到的HTTP消息中的内容，消息均以字符串的方式发送和接收
 */
public class ServerHTTPService implements IService, Runnable {
    //端口号
    private int port;

    //标志服务运行状态
    private boolean isActive;

    //监听状态为acceptable的SelectionKey的选择器
    private Selector listenerSelector;

    //监听状态为writable或readable的SelectionKey的选择器
    private Selector handlerSelector;

    //长连接管理者对象
    private Keeper keeper;

    public ServerHTTPService(int port) {
        this.port = port;
    }

    /**
     * 无参的初始化方法
     *
     * @throws IOException Selector或ServerSocketChannel会抛出IO异常
     */
    public void init() throws IOException {
        log("Starting HTTP service...");
        try {
            listenerSelector = Selector.open();
            handlerSelector = Selector.open();
            keeper = new Keeper();
            ServerSocketChannel httpChannel = ServerSocketChannel.open();
            httpChannel.socket().bind(new InetSocketAddress(port));
            log(String.format("HTTP service listen on port %d.", port));
            httpChannel.configureBlocking(false);
            httpChannel.register(listenerSelector, SelectionKey.OP_ACCEPT);
            log("HTTP service start successfully.");
            isActive = true;
        } catch (IOException e) {
            err("Failed to start HTTP service.");
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * 打印记录
     *
     * @param s 记录
     */
    private void log(String s) {
        System.out.println(
                '[' + this.toString() + " - "
                        + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()) + "] "
                        + s
        );
    }

    /**
     * 打印error级别的记录
     *
     * @param s 错误记录
     */
    private void err(String s) {
        System.err.println(
                '[' + this.toString() + " - "
                        + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()) + "] "
                        + s
        );
    }

    /**
     * 无参的运行方法，负责启动三个进程
     */
    @Override
    public void run() {
        new Thread(new Listener()).start();
        new Thread(new Handler()).start();
        new Thread(keeper).start();
    }

    /**
     * 读取参数中SelectionKey的输入流携带的的HTTP请求
     *
     * @param clientKey 输入流中有请求的SelectionKey
     * @throws IOException IO异常
     */
    private void read(SelectionKey clientKey) throws IOException {
        SocketChannel clientChannel = (SocketChannel) clientKey.channel();

        ByteBuffer readBuffer = ByteBuffer.allocate(1024);
        int count;
        StringBuilder sb = new StringBuilder();
        try {
            while ((count = clientChannel.read(readBuffer)) > 0) {
                sb.append(new String(readBuffer.array(), 0, count));
                readBuffer.clear();
            }
            String req = sb.toString();
            System.out.println(req);
            keeper.update(clientKey);//TODO: 根据请求头中的Connection属性值来维持或断开连接

            clientKey.attach("handling");
            String res = business(req);//由business业务方法来处理请求内容

            System.out.println(res);
            clientKey.attach(res);
        } catch (IOException e) {
            clientKey.cancel();
            clientChannel.socket().close();
            clientChannel.close();
            e.printStackTrace();
        } finally {
            if (clientKey.isValid()) {
                clientKey.interestOps(SelectionKey.OP_WRITE);
            }
        }
    }

    /**
     * 业务方法，负责解析和处理请求内容
     *
     * @param req 请求字符串
     * @return 响应字符串
     */
    private String business(String req) {
        //时间格式遵循RFC1123时间规范
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.CHINA);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        final String CRLF = HTTPMessage.getCRLF();
        ResponseMessage res = new ResponseMessage(200);

        //对请求切片
        List<String> headerFields = new ArrayList<>(Arrays.asList(req.substring(0, req.indexOf(CRLF + CRLF)).split(CRLF)));
        List<String> reqLineParts = new ArrayList<>(Arrays.asList(headerFields.get(0).split(" ")));
        headerFields.remove(0);
        HTTPMethod reqMethod = HTTPMethod.valueOf(reqLineParts.get(0).toUpperCase());
        String resource = reqLineParts.get(1);
        String content = req.substring(req.indexOf(CRLF + CRLF) + CRLF.length() * 2);

        URL url = this.getClass().getResource("/public" + resource);
        if (url == null) {
            res = new ResponseMessage(404);
            res.setProperty(
                    "Date",
                    sdf.format(new Date())
            );
            return res.toString();
        }
        if (reqMethod == HTTPMethod.POST) {
            //TODO: 处理POST方法
            try {
                Path resourcePath = Paths.get(url.toURI());
                if (!Files.exists(resourcePath)) {
                    res = new ResponseMessage(404);
                } else if (!Files.isDirectory(resourcePath)) {
                    res = new ResponseMessage(405);
                } else {
                    String content_type = "";
                    for (String field : headerFields) {
                        if (field.toLowerCase().startsWith("content-type")) {
                            content_type = field.split("[:]")[1];
                        }
                    }
                    try {
                        putFile(content_type, content.getBytes(), resource);
                    } catch (Exception e) {
                        e.printStackTrace();
                        res = new ResponseMessage(500);
                    }
                }
            } catch (URISyntaxException use) {
                res = new ResponseMessage(404);
            }
        } else {
            try {
                Path resourcePath = Paths.get(url.toURI());
                if (!Files.exists(resourcePath)) {
                    res = new ResponseMessage(404);
                } else if (Files.isDirectory(resourcePath)) {
                    if (resourcePath.equals(Paths.get(this.getClass().getResource("/public").toURI()))) {
                        res = new ResponseMessage(301);
                        res.setProperty(
                                "Location",
                                "http://127.0.0.1:8089/index.html"
                        );
                    } else {
                        res = new ResponseMessage(404);
                    }
                } else {
                    res.setProperty(
                            "Content-Type",
                            Files.probeContentType(resourcePath)
                    );
                    res.setProperty(
                            "Last-Modified",
                            sdf.format(new Date(Files.getLastModifiedTime(resourcePath).toMillis()))//资源最后一次修改时间
                    );
                    res.setContent(Files.readAllBytes(resourcePath));
                }
            } catch (URISyntaxException use) {
                res = new ResponseMessage(404);
            } catch (IOException ioe) {
                res = new ResponseMessage(500);
            }
        }
        res.setProperty(
                "Date",
                sdf.format(new Date())
        );
        return res.toString();
    }

    /**
     * 写入输出流方法
     *
     * @param key 待写入的SelectionKey
     * @throws IOException IO异常
     */
    private void write(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Object obj = key.attachment();
        key.attach("");
        channel.write(ByteBuffer.wrap(obj.toString().getBytes()));
        if (key.isValid()) {
            key.interestOps(SelectionKey.OP_READ);//设置为OP_READ否则会无限写入
        }
    }

    private void putFile(String content_type, byte[] content, String dir) throws Exception {
        if (dir.equals("/") || dir.equals("\\")) {
            dir = "";
        }
        if (!content_type.startsWith("text")) {
            content = Base64.getMimeDecoder().decode(content);
        }
        Path file = Paths.get(
                System.getProperty("user.dir"),
                "dustbin",
                dir,
                System.currentTimeMillis() + "." + MimeType.getPostfix(content_type)
        );
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
        log("Generate file at " + Files.write(file, content).toString());
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "@port:" + port;
    }

    /**
     * Inner Class Listener
     * 监听连接的选择器
     */
    private class Listener implements Runnable {
        @Override
        public void run() {
            while (isActive) {
                try {
                    if (listenerSelector.select(1) > 0) {
                        log("Comes a connection.");
                        Iterator<SelectionKey> selectionKeyIterator = listenerSelector.selectedKeys().iterator();

                        while (selectionKeyIterator.hasNext()) {
                            SelectionKey key = selectionKeyIterator.next();
                            try {
                                if (key.isAcceptable()) {
                                    SocketChannel clientChannel = ((ServerSocketChannel) key.channel()).accept();
                                    clientChannel.configureBlocking(false);
                                    clientChannel.register(handlerSelector, SelectionKey.OP_READ);
                                }
                            } finally {
                                selectionKeyIterator.remove();//必须从迭代器中移除
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Inner Class Handler
     * 监听可读写连接的选择器
     */
    private class Handler implements Runnable {
        @Override
        public void run() {
            while (isActive) {
                try {
                    if (handlerSelector.select(1) > 0) {
                        Iterator<SelectionKey> selectionKeyIterator = handlerSelector.selectedKeys().iterator();

                        while (selectionKeyIterator.hasNext()) {
                            SelectionKey clientKey = selectionKeyIterator.next();
                            try {
                                if (clientKey.isValid() && clientKey.isReadable()) {
                                    read(clientKey);
                                }
                                if (clientKey.isValid() && clientKey.isWritable()) {
                                    write(clientKey);
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            } finally {
                                selectionKeyIterator.remove();
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Inner Class Keeper
     * 监护者类维护计时来实现长连接
     */
    private class Keeper implements Runnable {
        private Map<SelectionKey, Long> lastConn;
        private long timeout = 10000;//允许的最大超市
        private long lazyTime = timeout / 10;//扫描的懒惰时间，所有连接的剩余超时都大于此值时休息一段时间，采用谜之系数

        Keeper() {
            lastConn = new ConcurrentHashMap<>();
        }

        @Override
        public synchronized void run() {
            while (isActive) {
                Iterator<Map.Entry<SelectionKey, Long>> iterator = lastConn.entrySet().iterator();
                boolean lazy = true;
                while (iterator.hasNext()) {
                    Map.Entry<SelectionKey, Long> conn = iterator.next();
                    SelectionKey key = conn.getKey();
                    if (key.attachment().equals("handling")) {
                        lastConn.replace(key, System.currentTimeMillis());
                        continue;
                    }
                    SocketChannel channel = (SocketChannel) key.channel();
                    long lastConnTimeMillis = conn.getValue();
                    if (System.currentTimeMillis() - lastConnTimeMillis > timeout) {
                        try {
                            log("Kill connection from " + channel.socket().getRemoteSocketAddress().toString());
                            key.cancel();
                            channel.socket().close();
                            channel.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        } finally {
                            iterator.remove();
                        }
                    } else if (System.currentTimeMillis() - lastConnTimeMillis < lazyTime * 0.67) {//又是谜之系数
                        lazy = false;
                    }
                }
                if (lazy) {
                    try {
                        Thread.sleep(lazyTime);
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }
                }
            }
        }

        /**
         * 在超时之内，客户端有请求发来，则重置计时器
         *
         * @param key 发来请求的SelectionKey
         */
        void update(SelectionKey key) {
            String address = ((SocketChannel) key.channel()).socket().getRemoteSocketAddress().toString();
            long current;
            for (SelectionKey k : lastConn.keySet()) {
                //通过IP来比对，不是很严谨
                String aliveAddress = ((SocketChannel) key.channel()).socket().getRemoteSocketAddress().toString();
                if (aliveAddress.equals(address)) {
                    current = System.currentTimeMillis();
                    lastConn.replace(k, current);
                    log("Connection with " + address + " update at " + current);
                    return;
                }
            }
            current = System.currentTimeMillis();
            lastConn.put(key, current);
            log("Connection with " + address + " create at " + current);
        }
    }
}
