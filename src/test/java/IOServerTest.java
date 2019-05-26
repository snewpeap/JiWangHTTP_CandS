import org.junit.After;
import org.junit.Before;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public class IOServerTest {
    private PrintStream out = null;

    @Before
    public void setUp() {
        out = System.out;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        System.setOut(new PrintStream(byteArrayOutputStream));
    }

    @After
    public void tearDown() {
        System.setOut(out);
    }

}
