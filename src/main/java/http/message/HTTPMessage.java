package http.message;

import java.util.*;

public abstract class HTTPMessage {
    protected static final String HTTP_VERSION = "HTTP/1.1";
    protected static final String CRLF = "\r\n";
    protected static List<String> set_property_split_ignore_case = new ArrayList<>();

    static {
        set_property_split_ignore_case.add("Date".toLowerCase());
    }

    private Map<String, List<String>> header;
    private byte[] content;

    public HTTPMessage() {
        this.header = new HashMap<>();
        this.content = null;
    }

    public static String getCRLF() {
        return CRLF;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }

    /**
     * 构建报文头
     * Format =
     * message-header = field-name ":" [ field-value ]
     * field-name     = token
     * field-value    = *( field-content | LWS )
     *
     * @param stringBuilder 已经添加起始行的StringBuilder
     * @return 构建好报文头的StringBuilder
     */
    protected StringBuilder buildHeader(StringBuilder stringBuilder) {
        Iterator<Map.Entry<String, List<String>>> iterator = header.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, List<String>> field = iterator.next();
            stringBuilder
                    .append(field.getKey()).append(":")
                    .append(String.join(",", field.getValue()))
                    .append(CRLF);
        }
        //报文头部末尾要附带一个CRLF
        stringBuilder.append(CRLF);

        return stringBuilder;
    }

    /**
     * 构建报文体
     *
     * @param stringBuilder 已经添加起始行和报文头的StringBuilder
     * @return 构建好报文体的StringBuilder
     */
    protected StringBuilder buildContent(StringBuilder stringBuilder) {
        if (content != null) {
            stringBuilder.append(new String(content));
        }

        return stringBuilder;
    }

    /**
     * 设置报文头参数，若传入空白参数字符串将不做任何操作
     *
     * @param key    参数名
     * @param values 参数字符串，参数之间用逗号(,)分隔
     */
    public void setProperty(String key, String values) {
        List<String> vl;
        if (set_property_split_ignore_case.contains(key.toLowerCase())) {
            vl = new ArrayList<>(1);
            vl.add(values);
        } else {
            vl = new ArrayList<>(Arrays.asList(values.split(",|[ ]")));
            vl.removeIf(
                    value -> value.trim().isEmpty()
            );
        }
        setProperty(key, vl);
    }

    /**
     * 设置报文参数，若传入空白参数列表将不做任何操作
     *
     * @param key    参数名
     * @param values 参数列表
     */
    private void setProperty(String key, List<String> values) {
        final String NOTHING_SET_MESSAGE = "Won't set this property due to no value was given.";

        if (key == null || key.trim().isEmpty()) {
            System.out.println("Property key could not be empty");
        } else if (values == null) {
            System.out.println("Property value could not be null");
        } else if (values.isEmpty()) {
            System.out.println(NOTHING_SET_MESSAGE);
        } else {
            values.removeIf(
                    value -> value.trim().isEmpty()//去除LMS
            );
            if (values.isEmpty()) {
                System.out.println(NOTHING_SET_MESSAGE);
            } else {
                header.put(key, values);
            }
        }
    }
}
