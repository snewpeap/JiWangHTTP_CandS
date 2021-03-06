package server.service.http;

import http.message.HTTPMessage;
import http.method.HTTPMethod;
import http.mime.MimeType;
import server.service.IService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.text.ParseException;
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

    //接收的文件放置的文件夹
    private Path receiveContentDir;

    //服务器的名字
    private String serverName = "JiWangHTTPServer/1.0";

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
        if (isActive) {
            log("HTTP service has already been initialized.");
            return;
        }
        try {
            listenerSelector = Selector.open();
            handlerSelector = Selector.open();
            keeper = new Keeper();

            ServerSocketChannel httpChannel = ServerSocketChannel.open();
            httpChannel.socket().bind(new InetSocketAddress(port));
            log("HTTP service listen on port " + port);
            httpChannel.configureBlocking(false);
            httpChannel.register(listenerSelector, SelectionKey.OP_ACCEPT);

            receiveContentDir = Paths.get(System.getProperty("user.dir") + "\\receive");
            if (!Files.exists(receiveContentDir)) {
                Files.createDirectory(receiveContentDir);
            }
            log("Set receive directory to " + receiveContentDir.toString());
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
        synchronized (System.out) {
            System.err.println(
                    '[' + this.toString() + " - "
                            + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()) + "] "
                            + s
            );
        }
    }

    /**
     * 无参的运行方法，负责启动三个进程
     */
    @Override
    public void run() {
        if (!isActive) {
            err("Service Not Yet initialized.");
            return;
        }
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
            keeper.update(clientKey);//一律保持长连接，不根据请求头中的Connection属性值来维持或断开连接

            clientKey.attach("handling");//标志该key正在处理中
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

        int endPosOfHeader = req.indexOf(CRLF + CRLF);
        //对请求切片
        List<String> headerFields = new ArrayList<>(Arrays.asList(req.substring(0, endPosOfHeader).split(CRLF)));
        List<String> reqLineParts = new ArrayList<>(Arrays.asList(headerFields.get(0).split(" ")));
        headerFields.remove(0);

        HTTPMethod reqMethod = HTTPMethod.valueOf(reqLineParts.get(0).toUpperCase());
        String resource = reqLineParts.get(1);
        String content = req.substring(endPosOfHeader + CRLF.length() * 2);

        URL publicResourceUrl = this.getClass().getResource("/public" + resource);//服务器公共资源都放在public文件夹下
        if (publicResourceUrl == null) {
            res = new ResponseMessage(404);
            res.setProperty(
                    "Date",
                    sdf.format(new Date())
            );
            return res.toString();
        }
        if (reqMethod == HTTPMethod.POST) {
            Path resourcePath = receiveContentDir.resolve(resource.equals("/") ? "" : resource);
            if (!Files.exists(resourcePath)) {
                res = new ResponseMessage(404);
            } else if (!Files.isDirectory(resourcePath)) {
                res = new ResponseMessage(405);
            } else {
                String content_type = getField(headerFields, "content-type");
                try {
                    String filename = putFile(content_type, content.getBytes(), resource);
                    //回应资源被保存的位置
                    res.setProperty("content-location", resource + filename);
                } catch (Exception e) {
                    e.printStackTrace();
                    res = new ResponseMessage(500);
                }
            }
        } else {
            try {
                Path resourcePath = getPath(publicResourceUrl.toURI());
                if (!Files.exists(resourcePath)) {
                    res = new ResponseMessage(404);
                } else if (Files.isDirectory(resourcePath)) {
                    if (resourcePath.equals(getPath(this.getClass().getResource("/public/").toURI()))) {
                        res = new ResponseMessage(301);
                        res.setProperty(
                                "Location",
                                "http://127.0.0.1:8089/index.html"
                        );
                    } else {
                        res = new ResponseMessage(404);
                    }
                } else {
                    boolean needContent = true;
                    Date last_modified = new Date(Files.getLastModifiedTime(resourcePath).toMillis());
                    String if_modified_since = getField(headerFields, "if-modified-since");
                    if (!if_modified_since.isEmpty()) {
                        Date since;
                        try {
                            since = sdf.parse(if_modified_since.trim());
                        } catch (ParseException pe) {
                            pe.printStackTrace();
                            since = new Date();
                        }
                        if (since.compareTo(last_modified) >= 0 // since.compareTo(last_modified)返回值≥0,since等于或晚于last_modified,说明资源的最晚修改时间早于since
                                || Math.abs(since.getTime() - last_modified.getTime()) < 1000) {// 而因为传来的since无法精确到毫秒，因此相差一秒以内则视为最晚修改时间早于since
                            res = new ResponseMessage(304);
                            needContent = false;
                        }
                    }
                    res.setProperty(
                            "Last-Modified",
                            sdf.format(last_modified)//资源最后一次修改时间
                    );
                    res.setProperty(
                            "ETag",
                            sdf.format(last_modified)//用资源最后一次修改时间充当ETag
                    );
                    if (needContent) {
                        String contentType = Files.probeContentType(resourcePath);
                        res.setProperty(
                                "Content-Type",
                                contentType
                        );
                        byte[] fileBytes = Files.readAllBytes(resourcePath);
                        if (contentType.toLowerCase().startsWith("image")) {
                            fileBytes = Base64.getMimeEncoder().encode(fileBytes);
                        }
                        res.setContent(fileBytes);
                    }
                }
            } catch (URISyntaxException use) {
                res = new ResponseMessage(404);
            } catch (IOException ioe) {
                res = new ResponseMessage(500);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        res.setProperty(
                "Date",
                sdf.format(new Date())
        );
        res.setProperty(
                "Server",
                serverName
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

    /**
     * 保存文件
     *
     * @param content_type content-type
     * @param content      内容字节流
     * @param subDir       相对于/receive的子路径
     * @return 保存文件的最终位置
     * @throws Exception exception
     */
    private String putFile(String content_type, byte[] content, String subDir) throws Exception {
        if (subDir.equals("/") || subDir.equals("\\")) {
            subDir = "";
        }
        if (!content_type.startsWith("text")) {
            //只有mime类型为text/*的资源不需要Base64编解码
            content = Base64.getMimeDecoder().decode(content);
        }
        String filename = System.currentTimeMillis() + "." + MimeType.getPostfix(content_type);
        Path file = receiveContentDir
                .resolve(subDir)
                .resolve(filename);//接收到的资源以接收时的毫秒数命名
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
        log("Generate file at " + Files.write(file, content).toString());
        return filename;
    }

    private String getField(List<String> headerFields, String key) {
        String field = "";
        for (String s : headerFields) {
            if (s.toLowerCase().startsWith(key.toLowerCase())) {
                field = s.substring(s.indexOf(':') + 1);
                break;
            }
        }
        return field;
    }

    private Path getPath(URI uri) throws IOException {
        if (uri.getScheme().equals("jar")) {
            final Map<String, String> env = new HashMap<>();
            final String[] array = uri.toString().split("[!]");
            final FileSystem fs;
            FileSystem temp;
            try {
                temp = FileSystems.newFileSystem(URI.create(array[0]), env);
            } catch (FileSystemAlreadyExistsException e) {
                temp = FileSystems.getFileSystem(URI.create(array[0]));
            }
            fs = temp;
            return fs.getPath(array[1]);
        } else {
            return Paths.get(uri);
        }
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
     * 采用比较笨的轮询方法
     *
     * 了解到可以用延时队列来实现，555
     */
    private class Keeper implements Runnable {
        private Map<SelectionKey, Long> lastConn;
        private long timeout = 10000;//允许的最大超时，10秒
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
                    //若还在处理状态则不断开连接
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
                        } catch (Exception e) {
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
                String aliveAddress = ((SocketChannel) key.channel()).socket().getRemoteSocketAddress().toString();
                // getRemoteSocketAddress().toString()获得远程主机的IP和port连接的字符串，如127.0.0.1:60000

                if (aliveAddress.equals(address)) {
                    current = System.currentTimeMillis();
                    lastConn.replace(k, current);
                    log("Connection with " + address + " update at " + current);
                    return;
                }
            }

            // 此连接是新来的连接，则新增一条记录
            current = System.currentTimeMillis();
            lastConn.put(key, current);
            log("Connection with " + address + " establish at " + current);
        }
    }
}
