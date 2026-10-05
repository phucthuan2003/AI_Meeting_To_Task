package vn.aimtt.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBodyLimitFilter extends OncePerRequestFilter {
    public static class BodyLimitExceeded extends IOException {}
    private final long limit;
    private final ObjectMapper mapper;

    public RequestBodyLimitFilter(@Value("${app.input.max-json-bytes:1048576}") long limit, ObjectMapper mapper) {
        this.limit = limit; this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        return type == null || !type.toLowerCase(Locale.ROOT).split(";", 2)[0].strip().endsWith("json");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > limit) {
            response.setStatus(413); response.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(response.getOutputStream(), ApiError.of(request, 413, "REQUEST_TOO_LARGE", "JSON request vượt giới hạn."));
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private ServletInputStream bounded;
            @Override public ServletInputStream getInputStream() throws IOException {
                if (bounded == null) {
                    ServletInputStream original = super.getInputStream();
                    bounded = new ServletInputStream() {
                        private long count;
                        private void check(int read) throws BodyLimitExceeded {
                            if (read > 0 && (count += read) > limit) throw new BodyLimitExceeded();
                        }
                        @Override public int read() throws IOException {
                            int value = original.read(); check(value < 0 ? 0 : 1); return value;
                        }
                        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                            int read = original.read(bytes, offset, length); check(read); return read;
                        }
                        @Override public boolean isFinished() { return original.isFinished(); }
                        @Override public boolean isReady() { return original.isReady(); }
                        @Override public void setReadListener(ReadListener listener) { original.setReadListener(listener); }
                        @Override public void close() throws IOException { original.close(); }
                    };
                }
                return bounded;
            }
            @Override public BufferedReader getReader() throws IOException {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        }, response);
    }
}
