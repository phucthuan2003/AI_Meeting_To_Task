package vn.aimtt.llm;

import java.net.http.HttpRequest;
import java.util.function.BooleanSupplier;

public interface LlmHttpTransport {
    record Response(int status, byte[] body) {}
    Response send(HttpRequest request, BooleanSupplier keepLease);
}
