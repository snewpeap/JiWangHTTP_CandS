package server.service;

import java.io.IOException;

public interface IService {
    void init() throws IOException;
    void run();
}
